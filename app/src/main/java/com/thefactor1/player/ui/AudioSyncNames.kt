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

    /** The same, short enough to sit beside the row's name in the player menu. */
    fun short(context: Context, ms: Int): String = when {
        ms == 0 -> context.getString(R.string.menu_in_sync)
        ms > 0 -> context.getString(R.string.audio_sync_late_short, ms)
        else -> context.getString(R.string.audio_sync_early_short, -ms)
    }

    fun hint(context: Context, ms: Int): String? = when {
        ms == 0 -> null
        ms > 0 -> context.getString(R.string.audio_sync_late_hint)
        else -> context.getString(R.string.audio_sync_early_hint)
    }
}
