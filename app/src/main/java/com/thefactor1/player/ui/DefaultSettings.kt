package com.thefactor1.player.ui

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import com.thefactor1.player.R
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode

/** The settings that apply to every video, as rows for the home screen. */
class DefaultSettings(private val context: Context, private val prefs: PlayerPrefs) {

    fun rows(): List<OptionList.RowSpec> = listOf(
        heading(R.string.settings_audio),
        item { audioLanguage() },
        heading(R.string.settings_subtitles),
        item { subtitleMode() },
        item { subtitleLanguage() },
        item { subtitleSize() },
        heading(R.string.settings_picture),
        item { pictureFit() },
        item { matchFrameRate() },
        heading(R.string.settings_advanced),
        item { audioOutput() },
    )

    private fun heading(text: Int) = OptionList.RowSpec.Heading(context.getString(text))
    private fun item(option: () -> Option) = OptionList.RowSpec.Item(option)

    private fun audioLanguage() = Option(
        context.getString(R.string.audio_default_language),
        languageName(prefs.audioLanguage, R.string.audio_language_auto),
        opens = languagePage(R.string.audio_default_language, R.string.audio_language_auto, { prefs.audioLanguage }) {
            prefs.audioLanguage = it
        },
    )

    private fun audioOutput() = Option(
        context.getString(R.string.audio_output),
        context.getString(if (prefs.decodeAudio) R.string.audio_output_decode else R.string.audio_output_passthrough),
        opens = OptionPage(context.getString(R.string.audio_output)) {
            listOf(
                Option(
                    context.getString(R.string.audio_output_passthrough),
                    context.getString(R.string.audio_output_passthrough_detail),
                    checked = !prefs.decodeAudio,
                ) { prefs.decodeAudio = false },
                Option(
                    context.getString(R.string.audio_output_decode),
                    context.getString(R.string.audio_output_decode_detail),
                    checked = prefs.decodeAudio,
                ) { prefs.decodeAudio = true },
            )
        },
    )

    private fun subtitleMode() = Option(
        context.getString(R.string.subs_default),
        context.getString(modeLabel(prefs.subtitleMode)),
        opens = OptionPage(context.getString(R.string.subs_default)) {
            SubtitleMode.entries.map { mode ->
                Option(context.getString(modeLabel(mode)), context.getString(modeDetail(mode)), prefs.subtitleMode == mode) {
                    prefs.subtitleMode = mode
                }
            }
        },
    )

    private fun subtitleLanguage() = Option(
        context.getString(R.string.subs_language),
        languageName(prefs.subtitleLanguage, R.string.subs_language_device) + " · " + context.getString(R.string.settings_subs_language_note),
        opens = languagePage(R.string.subs_language, R.string.subs_language_device, { prefs.subtitleLanguage }) {
            prefs.subtitleLanguage = it
        },
    )

    private fun subtitleSize() = Option(
        context.getString(R.string.subs_size),
        PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].name,
        opens = OptionPage(context.getString(R.string.subs_size)) {
            PlayerPrefs.SUBTITLE_SIZES.mapIndexed { index, size ->
                Option(size.name, checked = prefs.subtitleSize == index) { prefs.subtitleSize = index }
            }
        },
    )

    private fun pictureFit(): Option {
        val fits = listOf(
            Triple(R.string.picture_fit, R.string.picture_fit_detail, AspectRatioFrameLayout.RESIZE_MODE_FIT),
            Triple(R.string.picture_fill, R.string.picture_fill_detail, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            Triple(R.string.picture_stretch, R.string.picture_stretch_detail, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        )
        val current = fits.firstOrNull { it.third == prefs.resizeMode } ?: fits.first()
        return Option(
            context.getString(R.string.settings_picture_size),
            context.getString(current.first),
            opens = OptionPage(context.getString(R.string.settings_picture_size)) {
                fits.map { (label, detail, mode) ->
                    Option(context.getString(label), context.getString(detail), prefs.resizeMode == mode) { prefs.resizeMode = mode }
                }
            },
        )
    }

    private fun matchFrameRate() = Option(
        context.getString(R.string.picture_match),
        context.getString(if (prefs.matchFrameRate) R.string.picture_match_on else R.string.picture_match_off),
        checked = prefs.matchFrameRate,
    ) { prefs.matchFrameRate = !prefs.matchFrameRate }

    private fun languagePage(title: Int, noPreference: Int, current: () -> String, pick: (String) -> Unit) =
        OptionPage(context.getString(title)) {
            PlayerPrefs.LANGUAGES.map { language ->
                Option(language.name.ifEmpty { context.getString(noPreference) }, checked = current() == language.code) {
                    pick(language.code)
                }
            }
        }

    private fun languageName(code: String, noPreference: Int) =
        PlayerPrefs.LANGUAGES.firstOrNull { it.code == code && code.isNotEmpty() }?.name ?: context.getString(noPreference)

    private fun modeLabel(mode: SubtitleMode) = when (mode) {
        SubtitleMode.AUTO -> R.string.subs_mode_auto
        SubtitleMode.OFF -> R.string.subs_mode_off
        SubtitleMode.FORCED_ONLY -> R.string.subs_mode_forced
        SubtitleMode.ALWAYS -> R.string.subs_mode_always
    }

    private fun modeDetail(mode: SubtitleMode) = when (mode) {
        SubtitleMode.AUTO -> R.string.subs_mode_auto_detail
        SubtitleMode.OFF -> R.string.subs_mode_off_detail
        SubtitleMode.FORCED_ONLY -> R.string.subs_mode_forced_detail
        SubtitleMode.ALWAYS -> R.string.subs_mode_always_detail
    }
}
