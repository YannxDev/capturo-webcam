package io.github.yannick.capturowebcam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Petit serveur HTTP MJPEG : /video diffuse les images JPEG de la caméra
 * au format multipart/x-mixed-replace attendu par Capturo, et /audio
 * diffuse le micro du téléphone en PCM 48 kHz mono 16 bits.
 */
public class MjpegServer {

    public interface Listener {
        void onClientCountChanged(int count);
    }

    private static final int AUDIO_QUEUE_SIZE = 32;

    private final int port;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final Listener listener;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile boolean audioAvailable;
    private volatile byte[] latestFrame;

    public MjpegServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "capturo-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        audioAvailable = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
        latestFrame = null;
        notifyClients();
    }

    public boolean isRunning() {
        return running;
    }

    public void setAudioEnabled(boolean enabled) {
        audioAvailable = enabled;
        if (!enabled) {
            for (Client client : clients) {
                if (client.audio) {
                    client.close();
                }
            }
        }
    }

    public void publish(byte[] jpeg) {
        latestFrame = jpeg;
        for (Client client : clients) {
            if (!client.audio) {
                client.offer(jpeg);
            }
        }
    }

    public void publishAudio(byte[] pcm) {
        for (Client client : clients) {
            if (client.audio) {
                client.offerAudio(pcm);
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                Client client = new Client(socket);
                clients.add(client);
                notifyClients();
                client.start();
            } catch (IOException ignored) {
                // socket fermé à l'arrêt, ou erreur réseau ponctuelle
            }
        }
    }

    private void notifyClients() {
        if (listener != null) {
            listener.onClientCountChanged(clients.size());
        }
    }

    private final class Client implements Runnable {
        private final Socket socket;
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(1);
        private final BlockingQueue<byte[]> audioQueue =
                new ArrayBlockingQueue<>(AUDIO_QUEUE_SIZE);

        private volatile boolean audio;

        Client(Socket socket) {
            this.socket = socket;
        }

        void start() {
            Thread thread = new Thread(this, "capturo-client");
            thread.setDaemon(true);
            thread.start();
        }

        void offer(byte[] frame) {
            queue.poll();
            queue.offer(frame);
        }

        void offerAudio(byte[] chunk) {
            if (!audioQueue.offer(chunk)) {
                audioQueue.poll();
                audioQueue.offer(chunk);
            }
        }

        @Override
        public void run() {
            try (Socket ignored = socket) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                String path = parsePath(readRequest(in));

                if ("/audio".equals(path)) {
                    audio = true;
                    serveAudio(out);
                    return;
                }

                if (!"/video".equals(path)) {
                    serveInfo(out);
                    return;
                }

                audio = false;

                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Connection: close\r\n"
                        + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                        + "Pragma: no-cache\r\n"
                        + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();

                byte[] first = latestFrame;
                if (first != null) {
                    writeFrame(out, first);
                }

                while (running && !socket.isClosed()) {
                    byte[] frame = queue.poll(1, TimeUnit.SECONDS);
                    if (frame != null) {
                        writeFrame(out, frame);
                    }
                }
            } catch (Exception ignored) {
                // client parti
            } finally {
                clients.remove(this);
                notifyClients();
            }
        }

        private void serveAudio(OutputStream out) throws IOException, InterruptedException {
            if (!audioAvailable) {
                byte[] body = "Audio désactivé".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 503 Service Unavailable\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                return;
            }

            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Connection: close\r\n"
                    + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                    + "Content-Type: audio/L16;rate=48000;channels=1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            while (running && !socket.isClosed() && audioAvailable) {
                byte[] chunk = audioQueue.poll(1, TimeUnit.SECONDS);
                if (chunk != null) {
                    out.write(chunk);
                    out.flush();
                }
            }
        }

        private void serveInfo(OutputStream out) throws IOException {
            String host = socket.getLocalAddress().getHostAddress();
            String video = "http://" + host + ":" + port + "/video";
            String sound = "http://" + host + ":" + port + "/audio";
            byte[] body = (
                    "<!doctype html><html lang=\"fr\"><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width\">"
                    + "<title>Capturo Webcam</title>"
                    + "<body style=\"font-family:sans-serif;background:#1c1c1e;color:#fff;padding:2em\">"
                    + "<h1>Capturo Webcam</h1>"
                    + "<p>Flux MJPEG : <a style=\"color:#62a0ea\" href=\"/video\">/video</a></p>"
                    + "<p>Son PCM 48 kHz mono : <a style=\"color:#62a0ea\" href=\"/audio\">/audio</a>"
                    + (audioAvailable ? "" : " (désactivé)") + "</p>"
                    + "<p>Vidéo : <code>" + video + "</code></p>"
                    + "<p>Son : <code>" + sound + "</code></p>"
                    + "</body></html>"
            ).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        private void writeFrame(OutputStream out, byte[] frame) throws IOException {
            out.write("--frame\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write("Content-Type: image/jpeg\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(("Content-Length: " + frame.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(frame);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            buffer.write(value);
            int size = buffer.size();
            if (size >= 4) {
                byte[] data = buffer.toByteArray();
                if (data[size - 4] == '\r' && data[size - 3] == '\n'
                        && data[size - 2] == '\r' && data[size - 1] == '\n') {
                    break;
                }
            }
            if (size > 16384) {
                break;
            }
        }
        return buffer.toString("US-ASCII");
    }

    private static String parsePath(String request) {
        if (request == null || request.isEmpty()) {
            return null;
        }
        String[] lines = request.split("\r\n");
        if (lines.length == 0) {
            return null;
        }
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) {
            return null;
        }
        String path = parts[1];
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return path;
    }
}
