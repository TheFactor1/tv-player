package com.thefactor1.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat

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

    /** Lip-sync correction in ms; positive when sound arrives after the picture. */
    var audioDelayMs: Int
        get() = prefs.getInt(KEY_AUDIO_DELAY, 0)
        set(value) = prefs.edit { putInt(KEY_AUDIO_DELAY, value) }

    /** Evens out loud and quiet passages; needs decoded audio. */
    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_NIGHT_MODE, value) }

    /** Lifts voices over music and effects; needs decoded audio. */
    var dialogueBoost: Boolean
        get() = prefs.getBoolean(KEY_DIALOGUE_BOOST, false)
        set(value) = prefs.edit { putBoolean(KEY_DIALOGUE_BOOST, value) }

    /** On a screen without HDR, the player tone maps HDR video itself instead of leaving it to the TV box. */
    var hdrToSdr: Boolean
        get() = prefs.getBoolean(KEY_HDR_TO_SDR, true)
        set(value) = prefs.edit { putBoolean(KEY_HDR_TO_SDR, value) }

    /** Lets the film's own colour tint the seek bars. */
    var filmColours: Boolean
        get() = prefs.getBoolean(KEY_FILM_COLOURS, true)
        set(value) = prefs.edit { putBoolean(KEY_FILM_COLOURS, value) }

    /** Index into [SUBTITLE_STYLES]. */
    var subtitleStyle: Int
        get() = prefs.getInt(KEY_SUB_STYLE, 0).coerceIn(SUBTITLE_STYLES.indices)
        set(value) = prefs.edit { putInt(KEY_SUB_STYLE, value) }

    /** Index into [SUBTITLE_POSITIONS]. */
    var subtitlePosition: Int
        get() = prefs.getInt(KEY_SUB_POSITION, 0).coerceIn(SUBTITLE_POSITIONS.indices)
        set(value) = prefs.edit { putInt(KEY_SUB_POSITION, value) }

    var subtitleMode: SubtitleMode
        get() = SubtitleMode.entries.getOrElse(prefs.getInt(KEY_SUB_MODE, 0)) { SubtitleMode.AUTO }
        set(value) = prefs.edit { putInt(KEY_SUB_MODE, value.ordinal) }

    /** Language code from [LANGUAGES]; empty means the device's language. */
    var subtitleLanguage: String
        get() = prefs.getString(KEY_SUB_LANG, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SUB_LANG, value) }

    data class SubtitleSize(val name: String, val fraction: Float)

    data class SubtitleStyle(val name: String, val detail: String, val text: Int, val background: Int, val edgeType: Int)

    /** [lift] is the gap under the subtitles as a fraction of the picture's height. */
    data class SubtitlePosition(val name: String, val detail: String, val lift: Float)

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
        val SUBTITLE_STYLES = listOf(
            SubtitleStyle("Outline", "White with a black edge", WHITE, CLEAR, CaptionStyleCompat.EDGE_TYPE_OUTLINE),
            SubtitleStyle("Shadow", "White with a soft drop shadow", WHITE, CLEAR, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW),
            SubtitleStyle("Dark box", "White on a dark band, easiest to read on bright scenes", WHITE, BOX, CaptionStyleCompat.EDGE_TYPE_NONE),
            SubtitleStyle("Yellow", "Yellow with a black edge, like DVD subtitles", YELLOW, CLEAR, CaptionStyleCompat.EDGE_TYPE_OUTLINE),
        )
        val SUBTITLE_POSITIONS = listOf(
            SubtitlePosition("Bottom", "Close to the bottom edge", 0.08f),
            SubtitlePosition("Raised", "A little higher, clear of a projector's edge", 0.14f),
            SubtitlePosition("Higher", "Well inside the picture, for screens that crop", 0.20f),
        )
        /** Subtitle timing choices for one video, from lines early to lines late. */
        val SUBTITLE_DELAYS_MS = (-5_000..5_000 step 250).toList()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFE14D.toInt()
        private const val CLEAR = 0x00000000
        private const val BOX = 0xB3000000.toInt()

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
        private const val KEY_AUDIO_DELAY = "audio_delay_ms"
        /** Lip-sync choices, from sound early to sound late. */
        val AUDIO_DELAYS_MS = (-300..500 step 50).toList()
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_DIALOGUE_BOOST = "dialogue_boost"
        private const val KEY_FILM_COLOURS = "film_colours"
        private const val KEY_HDR_TO_SDR = "hdr_to_sdr"
        private const val KEY_SUB_STYLE = "subtitle_style"
        private const val KEY_SUB_POSITION = "subtitle_position"
        private const val KEY_SUB_MODE = "subtitle_mode"
        private const val KEY_SUB_LANG = "subtitle_language"
        private const val KEY_MATCH = "match_frame_rate"
        private const val KEY_SUB_SIZE = "subtitle_size"
        private const val KEY_RESIZE = "resize_mode"
    }
}
