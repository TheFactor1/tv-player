package com.thefactor1.player

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.ui.DefaultSettings
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.OptionList

/**
 * The home-screen entry: explains how to use the player, shows what this TV can output, and holds
 * the settings that apply to every video (the same ones the player's panels change).
 */
class MainActivity : Activity() {

    private lateinit var settings: OptionList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val fonts = Fonts(this)
        fonts.apply(fonts.bold, findViewById<TextView>(R.id.home_title), findViewById<TextView>(R.id.settings_title))
        fonts.apply(fonts.semibold, findViewById<TextView>(R.id.home_kicker), findViewById<TextView>(R.id.capabilities_title), findViewById<TextView>(R.id.settings_kicker))
        fonts.apply(fonts.medium, findViewById<TextView>(R.id.body), findViewById<TextView>(R.id.capabilities))
        findViewById<TextView>(R.id.capabilities).text = capabilityReport()

        val kicker = findViewById<TextView>(R.id.settings_kicker)
        val title = findViewById<TextView>(R.id.settings_title)
        val defaults = DefaultSettings(this, PlayerPrefs(this))
        settings = OptionList(this, findViewById<LinearLayout>(R.id.settings_items), fonts, onPageChanged = { page ->
            kicker.text = getString(if (page == null) R.string.settings_kicker else R.string.settings_title).uppercase()
            title.text = page?.title ?: getString(R.string.settings_title)
        }, root = defaults::rows)
        kicker.text = getString(R.string.settings_kicker)
        title.text = getString(R.string.settings_title)
        settings.show()
    }

    override fun onStart() {
        super.onStart()
        PhoneCompanion.start(this)
        // The code may have changed since the list was built: a phone paired, or the network came up.
        settings.show()
    }

    override fun onStop() {
        super.onStop()
        PhoneCompanion.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Left steps back out of a list of choices, like Back.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && settings.back()) return true
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Still delivered on every Android version this app supports without opting in to predictive back.")
    override fun onBackPressed() {
        if (settings.back()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
