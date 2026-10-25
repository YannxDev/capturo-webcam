package io.github.yannick.capturowebcam.service

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Petit serveur HTTP MJPEG :
 *  - /video : images JPEG en multipart/x-mixed-replace
 *  - /audio : micro du téléphone en PCM 48 kHz mono 16 bits
 *  - /      : page d'information (contient « Capturo Webcam » pour la sonde
 *             localhost de Capturo via adb forward).
 */
class MjpegServer(
    private val port: Int,
    private val onClientCount: (Int) -> Unit = {},
    private val tokenProvider: () -> String = { "" },
) {

    private companion object {
        const val AUDIO_QUEUE_SIZE = 32
        const val MAX_REQUEST_SIZE = 16 * 1024
    }

    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var audioAvailable = false

    @Volatile
    private var latestFrame: ByteArray? = null

    private var acceptThread: Thread? = null

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running = true
        acceptThread = Thread({ acceptLoop() }, "capturo-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        audioAvailable = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        latestFrame = null
        notifyClients()
    }

    fun isRunning(): Boolean = running

    fun setAudioEnabled(enabled: Boolean) {
        audioAvailable = enabled
        if (!enabled) {
            clients.filter { it.audio }.forEach { it.close() }
        }
    }

    fun publish(jpeg: ByteArray) {
        latestFrame = jpeg
        clients.forEach { client ->
            if (!client.audio) client.offer(jpeg)
        }
    }

    fun publishAudio(pcm: ByteArray) {
        clients.forEach { client ->
            if (client.audio) client.offerAudio(pcm)
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket?.accept() ?: break
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = 10_000
                val client = Client(socket)
                clients.add(client)
                notifyClients()
                client.start()
            } catch (_: IOException) {
                if (!running) break
            }
        }
    }

    private fun notifyClients() {
        onClientCount(clients.size)
    }

    private inner class Client(private val socket: Socket) {

        private val queue = ArrayBlockingQueue<ByteArray>(1)
        private val audioQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_SIZE)

        @Volatile
        var audio: Boolean = false

        private var thread: Thread? = null

        fun start() {
            thread = Thread({ run() }, "capturo-client").apply {
                isDaemon = true
                start()
            }
        }

        fun offer(frame: ByteArray) {
            queue.poll()
            queue.offer(frame)
        }

        fun offerAudio(chunk: ByteArray) {
            if (!audioQueue.offer(chunk)) {
                audioQueue.poll()
                audioQueue.offer(chunk)
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        private fun run() {
            try {
                socket.use { active ->
                    val input = active.getInputStream()
                    val output = active.getOutputStream()
                    val request = parseRequest(readRequest(input))
                    val protected = request.path == "/audio" || request.path == "/video"
                    val required = tokenProvider().trim()
                    if (protected && required.isNotEmpty() &&
                        !tokenMatches(request.token, required)
                    ) {
                        serveUnauthorized(output)
                        return
                    }
                    when (request.path) {
                        "/audio" -> {
                            audio = true
                            serveAudio(output)
                        }
                        "/video" -> {
                            audio = false
                            serveVideo(output)
                        }
                        else -> serveInfo(output, active)
                    }
                }
            } catch (_: Exception) {
                // Client parti : rien à signaler.
            } finally {
                clients.remove(this)
                notifyClients()
            }
        }

        private fun serveUnauthorized(output: OutputStream) {
            val body = (
                "Jeton d'accès requis. Ajoute ?token=… à l'adresse, " +
                    "ou recopie l'adresse affichée dans Capturo Webcam."
                ).toByteArray(Charsets.UTF_8)
            output.write(
                (
                    "HTTP/1.1 401 Unauthorized\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.write(body)
            output.flush()
        }

        private fun serveVideo(output: OutputStream) {
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()

            latestFrame?.let { writeFrame(output, it) }

            while (running && !socket.isClosed) {
                val frame = queue.poll(1, TimeUnit.SECONDS)
                if (frame != null) writeFrame(output, frame)
            }
        }

        private fun serveAudio(output: OutputStream) {
            if (!audioAvailable) {
                val body = "Audio désactivé".toByteArray(Charsets.UTF_8)
                output.write(
                    (
                        "HTTP/1.1 503 Service Unavailable\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                output.write(body)
                output.flush()
                return
            }

            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                        "Content-Type: audio/L16;rate=48000;channels=1\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()

            while (running && !socket.isClosed && audioAvailable) {
                val chunk = audioQueue.poll(1, TimeUnit.SECONDS)
                if (chunk != null) {
                    output.write(chunk)
                    output.flush()
                }
            }
        }

        private fun serveInfo(output: OutputStream, active: Socket) {
            val host = active.localAddress.hostAddress ?: "127.0.0.1"
            val video = "http://$host:$port/video"
            val sound = "http://$host:$port/audio"
            val protected = tokenProvider().isNotEmpty()
            val body = (
                "<!doctype html><html lang=\"fr\"><meta charset=\"utf-8\">" +
                    "<meta name=\"viewport\" content=\"width=device-width\">" +
                    "<title>Capturo Webcam</title>" +
                    "<body style=\"font-family:sans-serif;background:#101014;color:#fff;padding:2em\">" +
                    "<h1>Capturo Webcam</h1>" +
                    (if (protected) {
                        "<p>Flux protégé par un jeton d'accès : ajoute " +
                            "<code>?token=…</code> à l'adresse.</p>"
                    } else {
                        "<p>Flux MJPEG : <a style=\"color:#62a0ea\" href=\"/video\">/video</a></p>"
                    }) +
                    "<p>Son PCM 48 kHz mono : <a style=\"color:#62a0ea\" href=\"/audio\">/audio</a>" +
                    (if (audioAvailable) "" else " (désactivé)") + "</p>" +
                    "<p>Vidéo : <code>$video</code></p>" +
                    "<p>Son : <code>$sound</code></p>" +
                    "</body></html>"
                ).toByteArray(Charsets.UTF_8)
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.write(body)
            output.flush()
        }

        private fun writeFrame(output: OutputStream, frame: ByteArray) {
            output.write("--frame\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: image/jpeg\r\n".toByteArray(Charsets.US_ASCII))
            output.write(
                "Content-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            output.write(frame)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    private fun readRequest(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var value = input.read()
        while (value != -1) {
            buffer.write(value)
            val size = buffer.size()
            if (size >= 4) {
                val data = buffer.toByteArray()
                if (data[size - 4] == '\r'.code.toByte() &&
                    data[size - 3] == '\n'.code.toByte() &&
                    data[size - 2] == '\r'.code.toByte() &&
                    data[size - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            if (size > MAX_REQUEST_SIZE) break
            value = input.read()
        }
        return buffer.toString("US-ASCII")
    }

    private data class Request(val path: String?, val token: String?)

    private fun parseRequest(request: String): Request {
        if (request.isEmpty()) return Request(null, null)
        val firstLine = request.substringBefore("\r\n")
        val parts = firstLine.split(' ')
        if (parts.size < 2) return Request(null, null)
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val token = query.split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            ?.let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (_: IllegalArgumentException) {
                    it
                }
            }
        return Request(path, token)
    }

    private fun tokenMatches(provided: String?, required: String): Boolean {
        if (provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            required.toByteArray(Charsets.UTF_8),
        )
    }
}
