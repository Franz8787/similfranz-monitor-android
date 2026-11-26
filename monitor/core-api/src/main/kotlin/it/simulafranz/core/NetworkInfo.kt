// Portato da src/NetworkInfo.m (monitor iPad)
// SimulaFranz_Monitor — rilevamento dell'IP IPv4 Wi-Fi corrente (nessun valore fisso).
package it.simulafranz.core

import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkInfo {
    companion object {
        // IPv4 dell'interfaccia Wi-Fi (wlan0), null se non connessa.
        fun wifiIPv4Address(): String? {
            val list = try { NetworkInterface.getNetworkInterfaces() } catch (e: Exception) { null } ?: return null
            var result: String? = null
            for (a in list) {
                try {
                    if (!a.isUp || a.isLoopback) continue
                    if (a.name != "wlan0") continue     // ANDROID: wlan0 = Wi-Fi su Android (en0 sull'iPad)
                    for (addr in a.inetAddresses) {
                        if (addr !is Inet4Address) continue
                        val buf = addr.hostAddress
                        if (buf != null) { result = buf; break }
                    }
                    if (result != null) break
                } catch (e: Exception) {
                    continue
                }
            }
            return result
        }
    }
}
