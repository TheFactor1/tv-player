package com.thefactor1.player.launch

import android.content.Intent
import android.net.Uri

/**
 * The result handed back to the source app so it can save progress and offer resume. Apps copy
 * either MX Player's result (Int `position` and `duration`, plus `end_by`) or VLC's (Long
 * `extra_position` and `extra_duration`), so both are sent.
 */
object PlaybackResult {
    fun build(uri: Uri?, positionMs: Long, durationMs: Long, completed: Boolean): Intent =
        Intent(IntentContract.RESULT_ACTION).apply {
            uri?.let { data = it }
            val position = reportedPosition(positionMs, durationMs, completed)
            putExtra(IntentContract.EXTRA_POSITION, position.toIntClamped())
            putExtra(IntentContract.EXTRA_VLC_POSITION, position)
            if (durationMs > 0) {
                putExtra(IntentContract.EXTRA_DURATION, durationMs.toIntClamped())
                putExtra(IntentContract.EXTRA_VLC_DURATION, durationMs)
            }
            putExtra(
                IntentContract.EXTRA_END_BY,
                if (completed) IntentContract.END_BY_COMPLETION else IntentContract.END_BY_USER,
            )
        }

    /**
     * MX Player leaves the position out when the video finished, but some apps (Nuvio among them)
     * drop a result without one, so a finished video reports its full length instead. Apps that
     * read `end_by` still see the completion.
     */
    fun reportedPosition(positionMs: Long, durationMs: Long, completed: Boolean): Long =
        (if (completed && durationMs > 0) durationMs else positionMs).coerceAtLeast(0L)

    private fun Long.toIntClamped(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
