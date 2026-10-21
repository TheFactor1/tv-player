package com.thefactor1.player.ui

import android.content.Context
import com.thefactor1.player.R

/** How a lip-sync correction reads in the settings lists. */
object AudioSyncNames {
    fun label(context: Context, ms: Int): String = when {
        ms == 0 -> context.getString(R.string.audio_sync_none)
        ms > 0 -> context.getString(R.string.audio_sync_late, ms)
        else -> context.getString(R.string.audio_sync_early, -ms)
    }

    fun hint(context: Context, ms: Int): String? = when {
        ms == 0 -> null
        ms > 0 -> context.getString(R.string.audio_sync_late_hint)
        else -> context.getString(R.string.audio_sync_early_hint)
    }
}
