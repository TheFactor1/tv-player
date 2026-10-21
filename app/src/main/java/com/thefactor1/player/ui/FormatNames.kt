package com.thefactor1.player.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** Human names for codecs, channel layouts and languages, shared by the panels and the stats overlay. */
@OptIn(UnstableApi::class)
object FormatNames {

    fun codec(mime: String?): String = when (mime) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "WebVTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        else -> mime?.substringAfter('/')?.removePrefix("x-")?.uppercase(Locale.US) ?: "Unknown"
    }

    /** Subtitles parsed during extraction arrive as Media3 cues; the original format is in [Format.codecs]. */
    fun textCodec(format: Format): String =
        codec(if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType)

    fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "mono"
        count == 2 -> "stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count ch"
    }

    /** "English", or the track's own label, or null when neither is known. */
    fun language(format: Format): String? {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        val tag = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED } ?: return null
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        return name.ifBlank { tag }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    fun audioDetail(format: Format): String =
        listOfNotNull(codec(format.sampleMimeType), channels(format.channelCount)).joinToString(" ")
}
