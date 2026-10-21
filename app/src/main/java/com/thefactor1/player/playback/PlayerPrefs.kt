package com.thefactor1.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/** The few choices a viewer makes once and expects to stick. */
@OptIn(UnstableApi::class)
class PlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    var matchFrameRate: Boolean
        get() = prefs.getBoolean(KEY_MATCH, true)
        set(value) = prefs.edit { putBoolean(KEY_MATCH, value) }

    /** Index into [SUBTITLE_SIZES]. */
    var subtitleSize: Int
        get() = prefs.getInt(KEY_SUB_SIZE, 1).coerceIn(SUBTITLE_SIZES.indices)
        set(value) = prefs.edit { putInt(KEY_SUB_SIZE, value) }

    var resizeMode: Int
        get() = prefs.getInt(KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
        set(value) = prefs.edit { putInt(KEY_RESIZE, value) }

    data class SubtitleSize(val name: String, val fraction: Float)

    companion object {
        val SUBTITLE_SIZES = listOf(
            SubtitleSize("Small", 0.045f),
            SubtitleSize("Medium", 0.055f),
            SubtitleSize("Large", 0.068f),
        )
        private const val KEY_MATCH = "match_frame_rate"
        private const val KEY_SUB_SIZE = "subtitle_size"
        private const val KEY_RESIZE = "resize_mode"
    }
}
