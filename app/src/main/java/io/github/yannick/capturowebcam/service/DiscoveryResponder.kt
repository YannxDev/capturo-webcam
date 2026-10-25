package io.github.yannick.capturowebcam.service

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Répond à la découverte UDP de Capturo sur le port 8081.
 * Capturo envoie « CAPTURO_DISCOVER » sur chaque interface (Wi-Fi et USB)
 * et utilise l'adresse source de la réponse, qui est joignable depuis le PC.
 */
class DiscoveryResponder(
    private val videoPort: Int,
    private val tokenProvider: () -> String = { "" },
) {

    companion object {
        const val PORT = 8081
        const val REQUEST = "CAPTURO_DISCOVER"
        const val RESPONSE = "CAPTURO_WEBCAM"
        private const val TAG = "CapturoWebcam"
    }

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        try {
            val created = DatagramSocket(null)
            created.reuseAddress = true
            created.bind(InetSocketAddress(PORT))
            created.soTimeout = 1000
            socket = created
        } catch (error: SocketException) {
            Log.w(TAG, "Découverte UDP indisponible : ${error.message}")
            socket = null
            return
        }
        running = true
        thread = thread(name = "capturo-discovery", isDaemon = true) { loop() }
        Log.i(TAG, "Découverte UDP active sur le port $PORT")
    }

    @Synchronized
    fun stop() {
        running = false
        socket?.close()
        socket = null
        thread = null
    }

    private fun loop() {
        val buffer = ByteArray(256)
        while (running) {
            val current = socket ?: break
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                current.receive(packet)
                val message = String(
                    packet.data,
                    packet.offset,
                    packet.length,
                    Charsets.UTF_8,
                ).trim()
                if (message.startsWith(REQUEST)) {
                    val token = tokenProvider().trim()
                    val reply = buildString {
                        append(RESPONSE).append(' ').append(videoPort)
                        if (token.isNotEmpty()) append(' ').append(token)
                    }.toByteArray(Charsets.UTF_8)
                    current.send(
                        DatagramPacket(
                            reply,
                            reply.size,
                            packet.address,
                            packet.port,
                        ),
                    )
                }
            } catch (_: SocketTimeoutException) {
                // Boucle de contrôle.
            } catch (error: Exception) {
                if (running) {
                    Log.w(TAG, "Découverte : ${error.message}")
                }
            }
        }
    }
}
