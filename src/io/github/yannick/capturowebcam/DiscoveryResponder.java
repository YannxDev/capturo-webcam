package io.github.yannick.capturowebcam;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Répond aux messages de découverte UDP envoyés par Capturo sur le port 8081.
 * Capturo diffuse « CAPTURO_DISCOVER » sur le Wi-Fi et sur chaque interface
 * USB ; le téléphone répond avec son port vidéo. Capturo utilise l'adresse
 * source de la réponse, qui est joignable depuis l'ordinateur (Wi-Fi ou USB).
 */
public class DiscoveryResponder {

    public static final int PORT = 8081;
    public static final String REQUEST = "CAPTURO_DISCOVER";
    public static final String RESPONSE = "CAPTURO_WEBCAM";

    private final int videoPort;
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;

    public DiscoveryResponder(int videoPort) {
        this.videoPort = videoPort;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setSoTimeout(1000);
        } catch (SocketException error) {
            Log.w("CapturoWebcam",
                    "Découverte UDP indisponible : " + error.getMessage());
            socket = null;
            return;
        }
        running = true;
        thread = new Thread(this::loop, "capturo-discovery");
        thread.setDaemon(true);
        thread.start();
        Log.i("CapturoWebcam", "Découverte UDP active sur le port " + PORT);
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        thread = null;
    }

    private void loop() {
        byte[] buffer = new byte[256];
        while (running) {
            DatagramSocket current = socket;
            if (current == null) {
                break;
            }
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                current.receive(packet);
                String message = new String(
                        packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8).trim();
                if (message.startsWith(REQUEST)) {
                    byte[] reply = (RESPONSE + " " + videoPort)
                            .getBytes(StandardCharsets.UTF_8);
                    current.send(new DatagramPacket(
                            reply, reply.length,
                            packet.getAddress(), packet.getPort()));
                }
            } catch (SocketTimeoutException ignored) {
                // boucle de contrôle
            } catch (Exception error) {
                if (running) {
                    Log.w("CapturoWebcam",
                            "Découverte : " + error.getMessage());
                }
            }
        }
    }
}
