package com.thefactor1.player.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/** A TV Player found on the Wi-Fi. */
data class FoundTv(val name: String, val host: String, val port: Int)

/**
 * Finds TV Player on the home network: the TV advertises itself over mDNS while it's open.
 * Results arrive on the main thread. Resolving is one at a time, as older Androids require.
 */
class TvFinder(context: Context, private val onFound: (FoundTv) -> Unit) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    // Some phones drop multicast packets unless an app holds this while it listens.
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null) return
        lock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("tv-finder")?.apply { setReferenceCounted(false); acquire() }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    queue.addLast(info)
                    resolveNext()
                }
            }
        }
        listener = discovery
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        listener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
            }
        }
        listener = null
        queue.clear()
        lock?.release()
        lock = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done(null)
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                done(host?.let { FoundTv(info.serviceName, it, info.port) })
            }
        })
    }

    private fun done(found: FoundTv?) {
        main.post {
            resolving = false
            if (found != null && listener != null && !found.host.contains(':')) onFound(found)
            resolveNext()
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_tvplayer._tcp"
    }
}
