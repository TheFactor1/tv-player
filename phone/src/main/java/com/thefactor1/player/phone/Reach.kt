package com.thefactor1.player.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Why the phone can't get to the TV, as far as the phone itself can tell. */
enum class Unreachable {
    /** No network at all. */
    OFFLINE,
    /** Mobile data, no Wi-Fi. */
    MOBILE_DATA,
    /** A VPN carries the traffic, and most send the home network's addresses away too. */
    VPN,
    /** On Wi-Fi, but a different network from the one the TV was on. */
    OTHER_WIFI,
    /** The TV answered but no longer knows this phone: it was removed on the TV. */
    FORGOTTEN,
    /** Nothing wrong on the phone's side: the TV is asleep, off, or TV Player isn't open. */
    TV_SIDE,
}

object Reach {
    @Suppress("DEPRECATION")
    fun why(context: Context, tvHost: String?): Unreachable {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return Unreachable.TV_SIDE
        val active = connectivity.activeNetwork ?: return Unreachable.OFFLINE
        val caps = connectivity.getNetworkCapabilities(active) ?: return Unreachable.OFFLINE
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Unreachable.VPN
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) Unreachable.MOBILE_DATA else Unreachable.OFFLINE
        val host = tvHost ?: return Unreachable.TV_SIDE
        val links = connectivity.getLinkProperties(wifi)?.linkAddresses.orEmpty()
        val ours = links.mapNotNull { link -> (link.address as? Inet4Address)?.let { it to link.prefixLength } }
        if (ours.isEmpty()) return Unreachable.TV_SIDE
        return if (ours.any { (address, prefix) -> sameNetwork(address.hostAddress ?: "", host, prefix) }) Unreachable.TV_SIDE else Unreachable.OTHER_WIFI
    }

    /** Whether two IPv4 addresses share the first [prefix] bits. Anything unreadable counts as the same. */
    fun sameNetwork(a: String, b: String, prefix: Int): Boolean {
        val x = bits(a) ?: return true
        val y = bits(b) ?: return true
        val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL
        return (x and mask) == (y and mask)
    }

    private fun bits(address: String): Long? {
        val parts = address.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts.fold(0L) { sum, part -> (sum shl 8) or part.toLong() }
    }
}
