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

    /** Language code from [LANGUAGES]; empty means follow the file's own default track. */
    var audioLanguage: String
        get() = prefs.getString(KEY_AUDIO_LANG, "") ?: ""
        set(value) = prefs.edit { putString(KEY_AUDIO_LANG, value) }

    /** Decode every track to PCM instead of passing Dolby/DTS through to the receiver. */
    var decodeAudio: Boolean
        get() = prefs.getBoolean(KEY_DECODE_AUDIO, false)
        set(value) = prefs.edit { putBoolean(KEY_DECODE_AUDIO, value) }

    var subtitleMode: SubtitleMode
        get() = SubtitleMode.entries.getOrElse(prefs.getInt(KEY_SUB_MODE, 0)) { SubtitleMode.AUTO }
        set(value) = prefs.edit { putInt(KEY_SUB_MODE, value.ordinal) }

    /** Language code from [LANGUAGES]; empty means the device's language. */
    var subtitleLanguage: String
        get() = prefs.getString(KEY_SUB_LANG, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SUB_LANG, value) }

    data class SubtitleSize(val name: String, val fraction: Float)

    data class Language(val code: String, val name: String)

    enum class SubtitleMode {
        /** Whatever the file or the source app marks as default. */
        AUTO,
        OFF,
        FORCED_ONLY,
        ALWAYS,
    }

    companion object {
        val SUBTITLE_SIZES = listOf(
            SubtitleSize("Small", 0.045f),
            SubtitleSize("Medium", 0.055f),
            SubtitleSize("Large", 0.068f),
        )
        /** The first entry (empty code) means "no preference" and is labelled by each setting. */
        val LANGUAGES = listOf(
            Language("", ""),
            Language("en", "English"),
            Language("es", "Spanish"),
            Language("fr", "French"),
            Language("de", "German"),
            Language("it", "Italian"),
            Language("pt", "Portuguese"),
            Language("nl", "Dutch"),
            Language("ja", "Japanese"),
            Language("ko", "Korean"),
            Language("zh", "Chinese"),
            Language("hi", "Hindi"),
            Language("ar", "Arabic"),
        )
        private const val KEY_AUDIO_LANG = "audio_language"
        private const val KEY_DECODE_AUDIO = "decode_audio"
        private const val KEY_SUB_MODE = "subtitle_mode"
        private const val KEY_SUB_LANG = "subtitle_language"
        private const val KEY_MATCH = "match_frame_rate"
        private const val KEY_SUB_SIZE = "subtitle_size"
        private const val KEY_RESIZE = "resize_mode"
    }
}
