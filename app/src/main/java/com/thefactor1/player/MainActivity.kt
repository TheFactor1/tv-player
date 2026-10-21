package com.thefactor1.player

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/** The home-screen entry: explains how to use the player and shows what this TV can output. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.capabilities).text = capabilityReport()
    }

    @OptIn(UnstableApi::class)
    private fun capabilityReport(): String {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        val hdr = display.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "HDR type $it"
            }
        }.orEmpty()

        val audio = AudioCapabilities.getCapabilities(this, AudioAttributes.DEFAULT, null)
        val passthrough = listOf(
            C.ENCODING_AC3 to "Dolby Digital",
            C.ENCODING_E_AC3 to "Dolby Digital+",
            C.ENCODING_E_AC3_JOC to "DD+ Atmos",
            C.ENCODING_DOLBY_TRUEHD to "TrueHD",
            C.ENCODING_DTS to "DTS",
            C.ENCODING_DTS_HD to "DTS-HD",
        ).filter { (encoding, _) -> audio.supportsEncoding(encoding) }.map { it.second }

        val modes = display.supportedModes
            .map { "%.3f".format(it.refreshRate).trimEnd('0').trimEnd('.') }
            .distinct()

        return buildString {
            appendLine("HDR: ${hdr.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine("Passthrough: ${passthrough.ifEmpty { listOf("none (PCM only)") }.joinToString(", ")}")
            appendLine("Refresh rates: ${modes.joinToString(", ")} Hz")
            append("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }
}
