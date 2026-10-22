package com.thefactor1.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * How much of a stream is read ahead. Media3's defaults cap the read-ahead at about 140 MB, which
 * is only 15 seconds of a 4K remux, and half that at 2×: a moment's slowdown from a torrent or
 * Usenet source then runs the buffer dry. The cap here grows with the memory the app may use, so
 * faster playback has more to draw on. Media3 already asks for more media when playing faster
 * (its time targets are in seconds of playback, not seconds of film).
 */
@OptIn(UnstableApi::class)
object StreamBuffer {
    /** Seconds of playback to keep ahead, and the most ever held. */
    const val MIN_MS = 50_000
    const val MAX_MS = 120_000

    /** Seconds of playback buffered before starting, and before carrying on after a stall. */
    const val START_MS = 1_000
    // A stall means the source has fallen behind; carrying on after 2 s (Media3's default) just stalls again.
    const val AFTER_STALL_MS = 4_000

    /** Never less than Media3's own budget for a video, an audio and a subtitle track. */
    const val FLOOR_BYTES = DefaultLoadControl.DEFAULT_MUXED_BUFFER_SIZE
    const val CEILING_BYTES = 320 * 1024 * 1024

    /** Half of the app's memory limit, kept between the floor and the ceiling. */
    fun targetBytes(heapBytes: Long): Int =
        (heapBytes / 2).coerceIn(FLOOR_BYTES.toLong(), CEILING_BYTES.toLong()).toInt()

    fun loadControl(heapBytes: Long = Runtime.getRuntime().maxMemory()): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(MIN_MS, MAX_MS, START_MS, AFTER_STALL_MS)
            .setTargetBufferBytes(targetBytes(heapBytes))
            .build()
}
