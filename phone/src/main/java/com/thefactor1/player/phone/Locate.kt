package com.thefactor1.player.phone

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finds the paired TV: at its last address if it still answers there, else by name on the Wi-Fi. */
object Locate {
    private const val SEARCH_MS = 6_000L

    /** Blocks for up to a few seconds; call off the main thread. Null when the TV can't be reached. */
    fun current(context: Context, store: TvStore): TvApi? {
        store.api()?.takeIf { it.reachable() }?.let { return it }
        val name = store.name ?: return null
        val latch = CountDownLatch(1)
        var match: FoundTv? = null
        val finder = TvFinder(context) { tv ->
            if (tv.name == name && match == null) {
                match = tv
                latch.countDown()
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { finder.start() }
        latch.await(SEARCH_MS, TimeUnit.MILLISECONDS)
        android.os.Handler(android.os.Looper.getMainLooper()).post { finder.stop() }
        val tv = match ?: return null
        store.host = tv.host
        store.port = tv.port
        return store.api()?.takeIf { it.reachable() }
    }
}
