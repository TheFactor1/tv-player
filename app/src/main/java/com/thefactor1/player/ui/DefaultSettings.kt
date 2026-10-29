package com.thefactor1.player.ui

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import com.thefactor1.player.R
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.hub.HubVault
import com.thefactor1.player.hub.Quality
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode

/** The settings that apply to every video, and what this TV can do, as rows for the home screen's panel. */
class DefaultSettings(
    private val context: Context,
    private val prefs: PlayerPrefs,
    private val thisTv: () -> List<ThisTv.Fact>,
    /** Opens the hub address card on the home screen. */
    private val editHub: () -> Unit,
) {

    fun rows(): List<OptionList.RowSpec> = listOf(
        heading(R.string.settings_audio),
        item { audioLanguage() },
        item { audioSync() },
        item { nightMode() },
        item { dialogueBoost() },
        heading(R.string.settings_subtitles),
        item { subtitleMode() },
        item { subtitleLanguage() },
        item { subtitleStyle() },
        item { subtitleSize() },
        item { subtitlePosition() },
        heading(R.string.settings_picture),
        item { pictureFit() },
        item { matchFrameRate() },
        item { filmColours() },
        heading(R.string.settings_hub),
        item { hubAddress() },
        item { quality() },
        item { theme() },
        heading(R.string.settings_phone),
        item { phoneCompanion() },
        heading(R.string.settings_advanced),
        item { audioOutput() },
        heading(R.string.home_this_tv),
    ) + thisTv().map { fact -> item { Option(fact.label, fact.value, fact.note) } }

    private fun heading(text: Int) = OptionList.RowSpec.Heading(context.getString(text))
    private fun item(option: () -> Option) = OptionList.RowSpec.Item(option)

    private fun audioLanguage() = languageOption(R.string.audio_default_language, R.string.audio_language_auto,
        R.string.home_audio_language_hint, prefs.audioLanguage) { prefs.audioLanguage = it }

    private fun subtitleLanguage() = languageOption(R.string.subs_language, R.string.subs_language_device,
        R.string.settings_subs_language_note, prefs.subtitleLanguage) { prefs.subtitleLanguage = it }

    private fun audioSync(): Option {
        val delays = PlayerPrefs.AUDIO_DELAYS_MS
        return Option(
            context.getString(R.string.audio_sync),
            AudioSyncNames.short(context, prefs.audioDelayMs),
            AudioSyncNames.hint(context, prefs.audioDelayMs) ?: context.getString(R.string.audio_sync_none_hint),
            steps = Steps(delays.indexOf(prefs.audioDelayMs).coerceAtLeast(0), delays.size) { prefs.audioDelayMs = delays[it] },
        )
    }

    private fun nightMode() = Option(
        context.getString(R.string.night_mode),
        hint = context.getString(R.string.night_mode_detail),
        switch = true,
        checked = prefs.nightMode,
    ) { prefs.nightMode = !prefs.nightMode }

    private fun dialogueBoost() = Option(
        context.getString(R.string.dialogue_boost),
        hint = context.getString(R.string.dialogue_boost_detail),
        switch = true,
        checked = prefs.dialogueBoost,
    ) { prefs.dialogueBoost = !prefs.dialogueBoost }

    private fun subtitleMode(): Option {
        val modes = SubtitleMode.entries
        return Option(
            context.getString(R.string.subs_default),
            context.getString(modeLabel(prefs.subtitleMode)),
            context.getString(modeDetail(prefs.subtitleMode)),
            steps = Steps(prefs.subtitleMode.ordinal, modes.size) { prefs.subtitleMode = modes[it] },
        )
    }

    private fun subtitleStyle(): Option {
        val style = PlayerPrefs.SUBTITLE_STYLES[prefs.subtitleStyle]
        return Option(
            context.getString(R.string.subs_style), style.name, style.detail,
            steps = Steps(prefs.subtitleStyle, PlayerPrefs.SUBTITLE_STYLES.size) { prefs.subtitleStyle = it },
        )
    }

    private fun subtitleSize() = Option(
        context.getString(R.string.subs_size),
        PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].name,
        context.getString(R.string.menu_size_hint),
        steps = Steps(prefs.subtitleSize, PlayerPrefs.SUBTITLE_SIZES.size) { prefs.subtitleSize = it },
    )

    private fun subtitlePosition(): Option {
        val position = PlayerPrefs.SUBTITLE_POSITIONS[prefs.subtitlePosition]
        return Option(
            context.getString(R.string.subs_position), position.name, position.detail,
            steps = Steps(prefs.subtitlePosition, PlayerPrefs.SUBTITLE_POSITIONS.size) { prefs.subtitlePosition = it },
        )
    }

    private fun pictureFit(): Option {
        val fits = listOf(
            Triple(R.string.picture_fit, R.string.picture_fit_detail, AspectRatioFrameLayout.RESIZE_MODE_FIT),
            Triple(R.string.picture_fill, R.string.picture_fill_detail, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            Triple(R.string.picture_stretch, R.string.picture_stretch_detail, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        )
        val index = fits.indexOfFirst { it.third == prefs.resizeMode }.coerceAtLeast(0)
        return Option(
            context.getString(R.string.settings_picture_size),
            context.getString(fits[index].first),
            context.getString(fits[index].second),
            steps = Steps(index, fits.size) { prefs.resizeMode = fits[it].third },
        )
    }

    private fun matchFrameRate() = Option(
        context.getString(R.string.picture_match),
        hint = context.getString(if (prefs.matchFrameRate) R.string.picture_match_on else R.string.picture_match_off),
        switch = true,
        checked = prefs.matchFrameRate,
    ) { prefs.matchFrameRate = !prefs.matchFrameRate }

    private fun filmColours() = Option(
        context.getString(R.string.film_colours),
        hint = context.getString(R.string.film_colours_detail),
        switch = true,
        checked = prefs.filmColours,
    ) { prefs.filmColours = !prefs.filmColours }

    /** Where the phone page lives; OK shows the pairing card, with the QR and the only valid code. */
    private fun phoneCompanion(): Option {
        val address = PhoneCompanion.address()
        return Option(
            context.getString(R.string.companion_title),
            // The code shows on the pairing card only (it works only while that's up).
            address
                ?: context.getString(R.string.companion_offline_value),
            context.getString(if (address != null) R.string.pair_hint else R.string.companion_offline),
        ) { PairingCard.show(context, Fonts(context)) }
    }

    private fun hubAddress() = Option(
        context.getString(R.string.hub_address),
        HubVault(context).address.substringAfter("://").ifEmpty { context.getString(R.string.hub_address_none) },
        context.getString(R.string.hub_address_hint),
    ) { editHub() }

    private fun quality(): Option {
        val choices = Quality.CHOICES
        return Option(
            context.getString(R.string.quality),
            Quality.label(prefs.quality),
            context.getString(if (prefs.quality == Quality.AUTO) R.string.quality_auto_hint else R.string.quality_hint),
            steps = Steps(choices.indexOf(prefs.quality), choices.size) { prefs.quality = choices[it] },
        )
    }

    private fun theme(): Option {
        val themes = PlayerPrefs.THEMES
        val index = themes.indexOf(prefs.homeTheme)
        return Option(
            context.getString(R.string.theme),
            context.getString(if (prefs.homeTheme == PlayerPrefs.THEME_SPACE) R.string.theme_space else R.string.theme_mercury),
            context.getString(if (prefs.homeTheme == PlayerPrefs.THEME_SPACE) R.string.theme_space_hint else R.string.theme_mercury_hint),
            steps = Steps(index, themes.size) { prefs.homeTheme = themes[it] },
        )
    }

    private fun audioOutput() = Option(
        context.getString(R.string.audio_output),
        context.getString(if (prefs.decodeAudio) R.string.audio_output_decode else R.string.audio_output_passthrough),
        context.getString(if (prefs.decodeAudio) R.string.audio_output_decode_detail else R.string.audio_output_passthrough_detail),
        steps = Steps(if (prefs.decodeAudio) 1 else 0, 2) { prefs.decodeAudio = it == 1 },
    )

    private fun languageOption(title: Int, noPreference: Int, hint: Int, current: String, pick: (String) -> Unit): Option {
        val languages = PlayerPrefs.LANGUAGES
        val index = languages.indexOfFirst { it.code == current }.coerceAtLeast(0)
        return Option(
            context.getString(title),
            languages[index].name.ifEmpty { context.getString(noPreference) },
            context.getString(hint),
            steps = Steps(index, languages.size) { pick(languages[it].code) },
        )
    }

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
