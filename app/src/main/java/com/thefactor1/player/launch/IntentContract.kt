package com.thefactor1.player.launch

import androidx.media3.common.MimeTypes

/**
 * The extras Stremio, Nuvio and other apps send to an external player. They follow the
 * MX Player / Just Player convention, which is the de facto standard on Android.
 */
object IntentContract {
    const val EXTRA_TITLE = "title"
    const val EXTRA_POSITION = "position"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_HEADERS = "headers"
    const val EXTRA_SUBS = "subs"
    const val EXTRA_SUBS_NAME = "subs.name"
    const val EXTRA_SUBS_FILENAME = "subs.filename"
    const val EXTRA_SUBS_ENABLE = "subs.enable"
    const val EXTRA_RETURN_RESULT = "return_result"
    const val EXTRA_END_BY = "end_by"
    const val EXTRA_FILENAME = "filename"

    /** VLC's "ignore any saved position" switch. */
    const val EXTRA_FROM_START = "from_start"

    /** Vimu's start position, in ms. */
    const val EXTRA_START_FROM = "startfrom"

    /** VLC's result keys, as Longs. Apps written against VLC read these instead of [EXTRA_POSITION]. */
    const val EXTRA_VLC_POSITION = "extra_position"
    const val EXTRA_VLC_DURATION = "extra_duration"

    /** Android's own header bundle, used by some apps instead of [EXTRA_HEADERS]. */
    const val EXTRA_HTTP_HEADERS_BUNDLE = "android.media.intent.extra.HTTP_HEADERS"

    const val RESULT_ACTION = "com.mxtech.intent.result.VIEW"
    const val END_BY_USER = "user"
    const val END_BY_COMPLETION = "playback_completion"

    /** Headers arrive as a flat array: key, value, key, value. A trailing key without a value is dropped. */
    fun headersFromArray(array: Array<String>?): Map<String, String> {
        if (array == null) return emptyMap()
        val headers = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < array.size) {
            val key = array[i].trim()
            if (key.isNotEmpty()) headers[key] = array[i + 1]
            i += 2
        }
        return headers
    }

    /** Guesses a subtitle format from a file name or URL path, ignoring any query string. */
    fun subtitleMimeType(nameOrPath: String?): String {
        val clean = nameOrPath?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        return when {
            clean.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            clean.endsWith(".ass") || clean.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            clean.endsWith(".vtt") || clean.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            clean.endsWith(".ttml") || clean.endsWith(".dfxp") || clean.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            // Most addons serve SRT without an extension.
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /** Only adaptive-stream types help the player; a generic video type is left for it to sniff. */
    fun mediaMimeHint(intentType: String?): String? {
        val type = intentType?.lowercase() ?: return null
        return when {
            "mpegurl" in type -> MimeTypes.APPLICATION_M3U8
            "dash" in type -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    /**
     * A positive time in milliseconds. Apps disagree on the type, so Int, Long, Double and
     * numeric Strings are all accepted; anything else is 0.
     */
    fun positionFrom(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Float -> value.toLong()
        is Double -> value.toLong()
        is String -> value.trim().toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }.coerceAtLeast(0L)
}
