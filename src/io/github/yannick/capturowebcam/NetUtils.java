package io.github.yannick.capturowebcam;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Adresses IPv4 locales utilisables pour joindre le flux. */
public final class NetUtils {

    private NetUtils() {
    }

    public static List<String> urls(int port) {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return urls;
            }
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) {
                        urls.add("http://" + ip.getHostAddress() + ":" + port
                                + "/video   (" + network.getName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return urls;
    }
}
