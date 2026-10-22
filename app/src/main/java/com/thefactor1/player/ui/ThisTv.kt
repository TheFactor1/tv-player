package com.thefactor1.player.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.thefactor1.player.BuildConfig
import com.thefactor1.player.R
import com.thefactor1.player.playback.PlayerPrefs
import java.util.Locale

/** What this TV can show and play, in words: the facts at the end of the settings panel. */
object ThisTv {
    data class Fact(val label: String, val value: String, val note: String)

    @OptIn(UnstableApi::class)
    fun facts(activity: Activity, prefs: PlayerPrefs): List<Fact> {
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        return listOf(screen(activity, display), sound(activity, prefs), refresh(activity, display), box(activity))
    }

    private fun screen(activity: Activity, display: Display): Fact {
        @Suppress("DEPRECATION")
        val hdr = display.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> null
            }
        }.orEmpty().filterNotNull()
        val height = display.mode.physicalHeight.coerceAtMost(display.mode.physicalWidth)
        val resolution = "${height}p"
        return if (hdr.isEmpty()) {
            Fact(activity.getString(R.string.tv_screen), "$resolution · SDR", activity.getString(R.string.tv_screen_sdr))
        } else {
            Fact(activity.getString(R.string.tv_screen), "$resolution · HDR", activity.getString(R.string.tv_screen_hdr, hdr.joinToString(", ")))
        }
    }

    @OptIn(UnstableApi::class)
    private fun sound(activity: Activity, prefs: PlayerPrefs): Fact {
        val audio = AudioCapabilities.getCapabilities(activity, AudioAttributes.DEFAULT, null)
        val passthrough = listOf(
            C.ENCODING_E_AC3_JOC to "Atmos",
            C.ENCODING_DOLBY_TRUEHD to "TrueHD",
            C.ENCODING_E_AC3 to "DD+",
            C.ENCODING_AC3 to "DD",
            C.ENCODING_DTS_HD to "DTS-HD",
            C.ENCODING_DTS to "DTS",
        ).filter { (encoding, _) -> audio.supportsEncoding(encoding) }.map { it.second }
        val label = activity.getString(R.string.tv_sound)
        if (passthrough.isEmpty()) return Fact(label, activity.getString(R.string.tv_sound_pcm), activity.getString(R.string.tv_sound_pcm_note))
        val note = when {
            prefs.decodeAudio -> R.string.tv_sound_decoding
            "DTS" !in passthrough -> R.string.tv_sound_untouched_no_dts
            else -> R.string.tv_sound_untouched
        }
        return Fact(label, passthrough.joinToString(" · "), activity.getString(note))
    }

    private fun refresh(activity: Activity, display: Display): Fact {
        val rates = display.supportedModes.map { it.refreshRate }.distinct().sorted()
        val label = activity.getString(R.string.tv_refresh)
        if (rates.isEmpty()) return Fact(label, "", "")
        val value = if (rates.size == 1) "${rate(rates.first())} Hz" else activity.getString(R.string.tv_refresh_range, rate(rates.first()), rate(rates.last()))
        val film = rates.any { it in 23.9f..24.1f }
        return Fact(label, value, activity.getString(if (film) R.string.tv_refresh_film else R.string.tv_refresh_no_film))
    }

    private fun box(activity: Activity): Fact {
        // "onn. 4K Plus Streaming" already starts with its maker; others ("SHIELD Android TV") don't.
        val model = Build.MODEL.orEmpty()
        val name = if (model.lowercase(Locale.ROOT).startsWith(Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT))) {
            model
        } else {
            "${Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }} $model"
        }
        return Fact(activity.getString(R.string.tv_box), name, activity.getString(R.string.tv_box_note, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME))
    }

    private fun rate(rate: Float): String = String.format(Locale.US, "%.3f", rate).trimEnd('0').trimEnd('.')
}
