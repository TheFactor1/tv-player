package com.thefactor1.player.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Posters, backdrops and logos from the URLs the hub hands out: fetched on background threads,
 * decoded no larger than asked, and kept decoded in memory so scrolling back costs nothing.
 */
object HubImages {
    private const val CACHE_BYTES = 48 * 1024 * 1024
    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 10 * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Callbacks waiting on a download already under way, so a poster asked for twice is fetched once. */
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread({
            // Below the UI and playback: art can wait a frame, focus can't.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "hub-images").apply { isDaemon = true }
    }

    /** The cached picture for [url] at [width] px, if there is one; no download. */
    fun cached(url: String, width: Int): Bitmap? = cache.get(key(url, width))

    /**
     * Calls [ready] on the main thread with the picture, or null when it couldn't be had. The caller
     * checks the answer is still wanted (a row may have been reused for another title meanwhile).
     */
    fun load(url: String, width: Int, ready: (Bitmap?) -> Unit) {
        val key = key(url, width)
        cache.get(key)?.let { return ready(it) }
        synchronized(waiting) {
            waiting[key]?.let { it += ready; return }
            waiting[key] = mutableListOf(ready)
        }
        executor.execute {
            val bitmap = runCatching { fetch(url, width) }.getOrNull()
            bitmap?.let { cache.put(key, it) }
            val callbacks = synchronized(waiting) { waiting.remove(key) }.orEmpty()
            main.post { callbacks.forEach { it(bitmap) } }
        }
    }

    private fun key(url: String, width: Int) = "$width|$url"

    /** The whole body, or null past [MAX_BYTES]: no picture is that big, and memory isn't spent finding out. */
    private fun capped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
    }

    private fun fetch(url: String, width: Int): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { capped(it) } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        }
    }
}
