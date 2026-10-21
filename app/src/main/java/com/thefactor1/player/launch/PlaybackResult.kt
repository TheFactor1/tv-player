package com.thefactor1.player.launch

import android.content.Intent

/** The result handed back to the source app so it can save progress and offer resume. */
object PlaybackResult {
    fun build(positionMs: Long, durationMs: Long, completed: Boolean): Intent =
        Intent(IntentContract.RESULT_ACTION).apply {
            // Apps treat a missing position as "finished", so only send it when the viewer stopped early.
            if (!completed) putExtra(IntentContract.EXTRA_POSITION, positionMs.toIntClamped())
            if (durationMs > 0) putExtra(IntentContract.EXTRA_DURATION, durationMs.toIntClamped())
            putExtra(
                IntentContract.EXTRA_END_BY,
                if (completed) IntentContract.END_BY_COMPLETION else IntentContract.END_BY_USER,
            )
        }

    private fun Long.toIntClamped(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
