package com.thefactor1.player.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The display the picture goes out to, as the pre-show reports it. */
data class DisplayInfo(val width: Int, val height: Int, val refreshRate: Float, val hdrTypes: Set<HdrType>)

enum class HdrType { DOLBY_VISION, HDR10, HLG }

/**
 * The lines of the pre-show's projection log, built only from what the player found: the file's
 * picture and sound, what the display is actually given, and whether the frame rate is matched.
 */
@OptIn(UnstableApi::class)
object PreShowLines {

    /** "3840×2160 · HEVC 10-bit · Dolby Vision P8" */
    fun picture(format: Format): String {
        val size = if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null
        val depth = format.colorInfo?.lumaBitdepth?.takeIf { it > 8 }?.let { "$it-bit" }
        val codec = listOfNotNull(baseCodec(format), depth).joinToString(" ")
        return listOfNotNull(size, codec, hdrLabel(format)).joinToString(SEP)
    }

    /** "1920×1080 · shown as SDR" when an HDR picture goes to a display that can't show it. */
    fun output(format: Format?, display: DisplayInfo): String {
        val size = "${display.width}×${display.height}"
        val wanted = format?.let { hdrType(it) } ?: return size + SEP + "SDR"
        val shown = when {
            wanted in display.hdrTypes -> wanted
            // Dolby Vision streams carry an HDR10 base layer on most releases.
            wanted == HdrType.DOLBY_VISION && HdrType.HDR10 in display.hdrTypes -> HdrType.HDR10
            else -> null
        }
        return size + SEP + if (shown == null) "shown as SDR" else hdrName(shown)
    }

    /** "English · TrueHD 7.1 · passthrough"; [passthrough] is null until the audio output starts. */
    fun sound(format: Format, passthrough: Boolean?): String = listOfNotNull(
        FormatNames.language(format),
        FormatNames.audioDetail(format),
        passthrough?.let { if (it) "passthrough" else "decoded" },
    ).joinToString(SEP)

    /** "23.976 fps · display matched", or where the display was left when it isn't. */
    fun rate(fps: Float, displayHz: Float): String? {
        if (fps <= 0f) return null
        val display = if (matches(fps, displayHz)) "display matched" else "display at ${number(displayHz)} Hz"
        return "${number(fps)} fps$SEP$display"
    }

    /** A display at a whole multiple of the frame rate (48 Hz for 24 fps) shows every frame evenly too. */
    fun matches(fps: Float, displayHz: Float): Boolean {
        if (fps <= 0f || displayHz <= 0f) return false
        val ratio = displayHz / fps
        val whole = ratio.roundToInt()
        return whole >= 1 && abs(ratio - whole) < 0.0005f * whole
    }

    fun number(value: Float): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private fun baseCodec(format: Format): String {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return FormatNames.codec(format.sampleMimeType)
        val codecs = format.codecs.orEmpty()
        return when {
            codecs.startsWith("dvav") || codecs.startsWith("dva1") -> "H.264"
            codecs.startsWith("dav1") -> "AV1"
            else -> "HEVC"
        }
    }

    private fun hdrType(format: Format): HdrType? = when {
        format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> HdrType.DOLBY_VISION
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> HdrType.HDR10
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> HdrType.HLG
        else -> null
    }

    private fun hdrLabel(format: Format): String {
        val type = hdrType(format) ?: return "SDR"
        if (type != HdrType.DOLBY_VISION) return hdrName(type)
        // "dvhe.08.06": the second field is the profile.
        val profile = format.codecs?.split('.')?.getOrNull(1)?.toIntOrNull()
        return if (profile != null) "Dolby Vision P$profile" else "Dolby Vision"
    }

    private fun hdrName(type: HdrType): String = when (type) {
        HdrType.DOLBY_VISION -> "Dolby Vision"
        HdrType.HDR10 -> "HDR10"
        HdrType.HLG -> "HLG"
    }

    private const val SEP = " · "
}
