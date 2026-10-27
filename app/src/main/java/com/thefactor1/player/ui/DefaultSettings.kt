package com.thefactor1.player.ui

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import com.thefactor1.player.R
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode

/**
 * The settings that apply to every video, and what this TV can do, as pages for the home screen's
 * settings: the everyday choices on the first page, the subtitle look and the set-once rest one step in.
 * Each row names what it previews beside the list.
 */
class DefaultSettings(private val context: Context, private val prefs: PlayerPrefs, private val thisTv: () -> List<ThisTv.Fact>) {

    fun rows(page: String): List<OptionList.RowSpec> = when (page) {
        LOOK -> listOf(item { subtitleStyle() }, item { subtitleSize() }, item { subtitlePosition() })
        MORE -> listOf(
            item { audioSync() },
            item { audioOutput() },
            item { matchFrameRate() },
            item { hdrToSdr() },
            item { filmColours() },
            item { phoneCompanion() },
            item { aboutTv() },
        )
        TV -> thisTv().map { fact -> item { Option(fact.label, fact.value, fact.note) } }
        else -> listOf(
            item { subtitleMode() },
            item { subtitleLanguage() },
            item { subtitleLook() },
            item { audioLanguage() },
            item { enhance() },
            item { pictureFit() },
            item { more() },
        )
    }

    /** The page's name over its list. */
    fun title(page: String): String = context.getString(
        when (page) {
            LOOK -> R.string.setting_look
            MORE -> R.string.setting_more
            TV -> R.string.setting_about_tv
            else -> R.string.settings_title
        },
    )

    /** The page Back returns to, or null on the first. */
    fun parent(page: String): String? = when (page) {
        LOOK, MORE -> OptionList.ROOT
        TV -> MORE
        else -> null
    }

    private fun item(option: () -> Option) = OptionList.RowSpec.Item(option)

    private fun subtitleLook() = Option(
        context.getString(R.string.setting_look),
        context.getString(R.string.setting_look_value, PlayerPrefs.SUBTITLE_STYLES[prefs.subtitleStyle].name,
            PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].name),
        context.getString(R.string.setting_look_hint),
        opens = LOOK,
        preview = Preview.SUBTITLES,
    )

    private fun more() = Option(
        context.getString(R.string.setting_more),
        context.getString(R.string.setting_more_value),
        context.getString(R.string.setting_more_hint),
        opens = MORE,
    )

    private fun aboutTv() = Option(
        context.getString(R.string.setting_about_tv),
        thisTv().firstOrNull()?.value,
        context.getString(R.string.setting_about_tv_hint),
        opens = TV,
    )

    private fun audioLanguage() = languageOption(R.string.setting_spoken_language, R.string.audio_language_auto,
        R.string.home_audio_language_hint, prefs.audioLanguage, Preview.SOUND) { prefs.audioLanguage = it }

    private fun subtitleLanguage() = languageOption(R.string.setting_subtitle_language, R.string.subs_language_device,
        R.string.settings_subs_language_note, prefs.subtitleLanguage, Preview.SUBTITLES) { prefs.subtitleLanguage = it }

    private fun audioSync(): Option {
        val delays = PlayerPrefs.AUDIO_DELAYS_MS
        return Option(
            context.getString(R.string.setting_sync),
            AudioSyncNames.short(context, prefs.audioDelayMs),
            context.getString(R.string.settings_sync_hint),
            steps = Steps(delays.indexOf(prefs.audioDelayMs).coerceAtLeast(0), delays.size) { prefs.audioDelayMs = delays[it] },
            preview = Preview.SOUND,
        )
    }

    /** Night mode and dialogue boost as one row: off, either, or both. */
    private fun enhance(): Option {
        val index = (if (prefs.nightMode) 1 else 0) + (if (prefs.dialogueBoost) 2 else 0)
        val names = listOf(R.string.enhance_off, R.string.enhance_night, R.string.enhance_dialogue, R.string.enhance_both)
        val hints = listOf(R.string.enhance_off_detail, R.string.night_mode_detail, R.string.dialogue_boost_detail, R.string.enhance_both_detail)
        return Option(
            context.getString(R.string.setting_enhance),
            context.getString(names[index]),
            context.getString(hints[index]),
            steps = Steps(index, names.size) {
                prefs.nightMode = it and 1 != 0
                prefs.dialogueBoost = it and 2 != 0
            },
            preview = Preview.SOUND,
        )
    }

    private fun subtitleMode(): Option {
        val modes = SubtitleMode.entries
        return Option(
            context.getString(R.string.setting_subtitles),
            context.getString(modeLabel(prefs.subtitleMode)),
            context.getString(modeDetail(prefs.subtitleMode)),
            steps = Steps(prefs.subtitleMode.ordinal, modes.size) { prefs.subtitleMode = modes[it] },
            preview = Preview.SUBTITLES,
        )
    }

    private fun subtitleStyle(): Option {
        val style = PlayerPrefs.SUBTITLE_STYLES[prefs.subtitleStyle]
        return Option(
            context.getString(R.string.setting_style), style.name, style.detail,
            steps = Steps(prefs.subtitleStyle, PlayerPrefs.SUBTITLE_STYLES.size) { prefs.subtitleStyle = it },
            preview = Preview.SUBTITLES,
        )
    }

    private fun subtitleSize() = Option(
        context.getString(R.string.setting_size),
        PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].name,
        context.getString(R.string.menu_size_hint),
        steps = Steps(prefs.subtitleSize, PlayerPrefs.SUBTITLE_SIZES.size) { prefs.subtitleSize = it },
        preview = Preview.SUBTITLES,
    )

    private fun subtitlePosition(): Option {
        val position = PlayerPrefs.SUBTITLE_POSITIONS[prefs.subtitlePosition]
        return Option(
            context.getString(R.string.setting_position), position.name, position.detail,
            steps = Steps(prefs.subtitlePosition, PlayerPrefs.SUBTITLE_POSITIONS.size) { prefs.subtitlePosition = it },
            preview = Preview.SUBTITLES,
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
            context.getString(R.string.setting_shape),
            context.getString(fits[index].first),
            context.getString(fits[index].second),
            steps = Steps(index, fits.size) { prefs.resizeMode = fits[it].third },
            preview = Preview.SHAPE,
        )
    }

    private fun matchFrameRate() = Option(
        context.getString(R.string.picture_match),
        hint = context.getString(if (prefs.matchFrameRate) R.string.picture_match_on else R.string.picture_match_off),
        switch = true,
        checked = prefs.matchFrameRate,
    ) { prefs.matchFrameRate = !prefs.matchFrameRate }

    private fun hdrToSdr() = Option(
        context.getString(R.string.picture_hdr_to_sdr),
        hint = context.getString(R.string.settings_hdr_hint),
        switch = true,
        checked = prefs.hdrToSdr,
    ) { prefs.hdrToSdr = !prefs.hdrToSdr }

    private fun filmColours() = Option(
        context.getString(R.string.film_colours),
        hint = context.getString(R.string.film_colours_detail),
        switch = true,
        checked = prefs.filmColours,
    ) { prefs.filmColours = !prefs.filmColours }

    /** Where the phone page lives and the code to pair a new phone; OK shows the QR code. */
    private fun phoneCompanion(): Option {
        val address = PhoneCompanion.address()
        return Option(
            context.getString(R.string.companion_title),
            address?.let { context.getString(R.string.companion_value, it, PhoneCompanion.code) }
                ?: context.getString(R.string.companion_offline_value),
            context.getString(if (address != null) R.string.pair_hint else R.string.companion_offline),
        ) { PairingCard.show(context, Fonts(context)) }
    }

    private fun audioOutput() = Option(
        context.getString(R.string.setting_output),
        context.getString(if (prefs.decodeAudio) R.string.audio_output_decode else R.string.audio_output_passthrough),
        context.getString(if (prefs.decodeAudio) R.string.audio_output_decode_detail else R.string.audio_output_passthrough_detail),
        steps = Steps(if (prefs.decodeAudio) 1 else 0, 2) { prefs.decodeAudio = it == 1 },
        preview = Preview.SOUND,
    )

    private fun languageOption(title: Int, noPreference: Int, hint: Int, current: String, preview: Preview, pick: (String) -> Unit): Option {
        val languages = PlayerPrefs.LANGUAGES
        val index = languages.indexOfFirst { it.code == current }.coerceAtLeast(0)
        return Option(
            context.getString(title),
            languages[index].name.ifEmpty { context.getString(noPreference) },
            context.getString(hint),
            steps = Steps(index, languages.size) { pick(languages[it].code) },
            preview = preview,
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

    companion object {
        private const val LOOK = "look"
        private const val MORE = "more"
        private const val TV = "tv"
    }
}
