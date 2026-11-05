package com.thefactor1.player.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fetches a stream's chapters off the main thread with a few small ranged reads of its own,
 * beside the player's connection, and hands them back on the main thread. Only web streams
 * are read; a server that ignores ranges is read from the start only.
 */
class ChapterLoader(private val userAgent: String) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "chapters").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    /** Reads [uri]'s chapters and calls [done] with them, unless [cancel] or another load came first. */
    fun load(uri: Uri, headers: Map<String, String>, done: (List<Chapter>) -> Unit) {
        val mine = ++generation
        if (uri.scheme != "http" && uri.scheme != "https") return
        executor.execute {
            val chapters = try {
                MatroskaChapters.read { offset, length -> if (generation == mine) readRange(uri, headers, offset, length) else null }
            } catch (e: RuntimeException) {
                // A file that breaks the reader has no chapters, not a crash.
                Log.w(TAG, "chapters unreadable", e)
                emptyList()
            }
            Log.i(TAG, "${chapters.size} chapters")
            main.post { if (generation == mine) done(chapters) }
        }
    }

    fun cancel() {
        generation++
    }

    private fun readRange(uri: Uri, headers: Map<String, String>, offset: Long, length: Int): ByteArray? {
        val connection = try {
            URL(uri.toString()).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                // No ranges: the start can still be read, nothing further in.
                HttpURLConnection.HTTP_OK -> if (offset != 0L) return null
                else -> return null
            }
            val out = ByteArray(length)
            var read = 0
            connection.inputStream.use { input ->
                while (read < length) {
                    val n = input.read(out, read, length - read)
                    if (n < 0) break
                    read += n
                }
            }
            out.copyOf(read)
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "Chapters"
        const val TIMEOUT_MS = 10_000
    }
}
