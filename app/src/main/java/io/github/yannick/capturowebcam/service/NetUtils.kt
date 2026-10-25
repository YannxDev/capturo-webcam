package io.github.yannick.capturowebcam.service

import io.github.yannick.capturowebcam.data.UrlInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Collections

/** Adresses IPv4 locales utilisables par Capturo (Wi-Fi, Ethernet, USB). */
object NetUtils {

    fun urls(port: Int, token: String = ""): List<UrlInfo> {
        val suffix = token.trim().let {
            if (it.isEmpty()) "" else "?token=" + URLEncoder.encode(it, "UTF-8")
        }
        val result = LinkedHashMap<String, UrlInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (network in Collections.list(interfaces)) {
                if (!network.isUp || network.isLoopback) continue
                val name = network.name.lowercase()
                if (name.startsWith("rmnet") ||
                    name.startsWith("p2p") ||
                    name.startsWith("dummy")
                ) {
                    continue
                }
                for (address in Collections.list(network.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val ip = address.hostAddress ?: continue
                    if (ip.startsWith("169.254.")) continue
                    val usb = name.startsWith("rndis") ||
                        name.startsWith("usb") ||
                        name.startsWith("ncm") ||
                        ip.startsWith("192.168.42.") ||
                        ip.startsWith("192.168.137.") ||
                        ip.startsWith("192.168.49.")
                    val label = when {
                        usb -> "USB"
                        name.startsWith("wlan") || name.startsWith("wifi") -> "Wi-Fi"
                        else -> "Réseau"
                    }
                    result[ip] = UrlInfo(
                        label = label,
                        video = "http://$ip:$port/video$suffix",
                        audio = "http://$ip:$port/audio$suffix",
                        interfaceName = network.name,
                    )
                }
            }
        } catch (_: Exception) {
            // Réseau indisponible : la liste restera vide.
        }
        return result.values.sortedWith(
            compareByDescending<UrlInfo> { it.label == "USB" }
                .thenBy { it.label }
                .thenBy { it.video },
        )
    }
}
