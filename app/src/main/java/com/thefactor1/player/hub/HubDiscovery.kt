package com.thefactor1.player.hub

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A hub that answered the beacon: its name and the address its API is on. */
data class FoundHub(val name: String, val address: String)

/**
 * Finds hubs on the LAN: `MEDIABOX?` is broadcast to UDP 8098 every 2 s for up to 20 s, and each
 * hub answers `MEDIABOX 1 <port> <name>` from its own IP. Runs on its own thread; answers arrive on
 * the main thread.
 */
class HubDiscovery(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val main = Handler(Looper.getMainLooper())
    /** Bumped by every start and stop, so an older search ends quietly. */
    @Volatile private var ticket = 0

    /** [found] is called once per hub; [finished] when the search ends on its own (not after [stop]). */
    fun start(found: (FoundHub) -> Unit, finished: () -> Unit) {
        val mine = ++ticket
        Thread({ search(mine, found, finished) }, "hub-discovery").apply { isDaemon = true }.start()
    }

    fun stop() {
        ticket++
    }

    private fun search(mine: Int, found: (FoundHub) -> Unit, finished: () -> Unit) {
        val running = { ticket == mine }
        // Some Wi-Fi drivers drop broadcast traffic unless an app holds this lock.
        val lock = wifi?.createMulticastLock("hub-discovery")?.apply { setReferenceCounted(false) }
        val seen = HashSet<String>()
        try {
            lock?.acquire()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = RECEIVE_MS
                val ask = request()
                val started = System.currentTimeMillis()
                var lastSent = 0L
                val buffer = ByteArray(512)
                while (running() && System.currentTimeMillis() - started < SEARCH_MS) {
                    if (System.currentTimeMillis() - lastSent >= INTERVAL_MS) {
                        lastSent = System.currentTimeMillis()
                        targets().forEach { target -> runCatching { socket.send(DatagramPacket(ask, ask.size, target, PORT)) } }
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val ip = packet.address.hostAddress ?: continue
                    val hub = parse(String(packet.data, 0, packet.length, Charsets.UTF_8), ip) ?: continue
                    if (seen.add(hub.address)) main.post { if (running()) found(hub) }
                }
            }
        } catch (e: Exception) {
            // No network at all: the search just ends, and the typed address is offered.
        } finally {
            runCatching { lock?.release() }
            main.post { if (running()) finished() }
        }
    }

    /** The all-ones broadcast, plus each interface's subnet broadcast (some routers drop the first). */
    private fun targets(): Set<InetAddress> {
        val subnets = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.mapNotNull { address -> address.broadcast } }
        }.getOrDefault(emptyList())
        return (listOf(InetAddress.getByName("255.255.255.255")) + subnets).toSet()
    }

    companion object {
        const val PORT = 8098
        private const val BEACON = "MEDIABOX?"
        /** The hub only answers a request at least as long as its reply, so it can't be used to amplify traffic. */
        const val REQUEST_BYTES = 64

        /** "MEDIABOX?" padded with spaces to [REQUEST_BYTES]; the hub matches the trimmed text. */
        fun request(): ByteArray = BEACON.padEnd(REQUEST_BYTES, ' ').toByteArray(Charsets.US_ASCII)
        private const val INTERVAL_MS = 2_000L
        private const val SEARCH_MS = 20_000L
        private const val RECEIVE_MS = 500

        /** "MEDIABOX 1 8097 Living room" from [ip] is a hub at http://ip:8097; anything else is ignored. */
        fun parse(reply: String, ip: String): FoundHub? {
            val parts = reply.trim().split(Regex("""\s+"""), limit = 4)
            if (parts.size < 3 || parts[0] != "MEDIABOX" || parts[1] != "1") return null
            val port = parts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val host = if (':' in ip) "[${ip.substringBefore('%')}]" else ip
            return FoundHub(parts.getOrNull(3)?.trim()?.ifEmpty { null } ?: ip, "http://$host:$port")
        }
    }
}
