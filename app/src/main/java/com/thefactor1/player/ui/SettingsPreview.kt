package com.thefactor1.player.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.thefactor1.player.R
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.playback.PlayerPrefs.SubtitleMode

/**
 * The scene beside the settings list: what the focused setting does, shown rather than told. The
 * subtitle line takes the chosen style, size and height on the film's own art, the picture crops or
 * stretches for its shape, the sound bars even out for night mode and lift the voices for dialogue
 * boost. Under it, the row's name and a line about it. Changes glide; nothing jumps.
 */
@OptIn(UnstableApi::class)
class SettingsPreview(root: View, private val prefs: PlayerPrefs, private val fonts: Fonts) {
    private val context = root.context
    private val frame: View = root.findViewById(R.id.settings_preview)
    // A wide film inside the 16:9 frame; its shape setting scales it.
    private val film: AspectFrame = root.findViewById(R.id.settings_preview_film)
    private val art: ImageView = root.findViewById(R.id.settings_preview_art)
    private val subtitles: SubtitleView = root.findViewById(R.id.settings_preview_subtitles)
    private val sound: View = root.findViewById(R.id.settings_preview_sound)
    private val bars: SoundBars = root.findViewById(R.id.settings_preview_bars)
    private val title: TextView = root.findViewById(R.id.settings_preview_title)
    private val hint: TextView = root.findViewById(R.id.settings_hint)

    private var shown: Preview? = null

    init {
        frame.clipToOutline = true
        film.ratio = FILM_RATIO
        subtitles.setCues(listOf(Cue.Builder().setText(context.getString(R.string.preview_subtitle)).build()))
        title.typeface = fonts.bold
        hint.typeface = fonts.medium
    }

    /** The art behind the scene: the home screen's own, or none before anything has played. */
    fun setArt(drawable: Drawable?) {
        // A copy, so the backdrop's own drawable keeps its bounds.
        art.setImageDrawable(drawable?.constantState?.newDrawable(context.resources) ?: drawable)
    }

    /** Shows what [option] changes, as it is set now. The first call after [reset] sets it without gliding. */
    fun show(option: Option) {
        val animate = shown != null
        val kind = option.preview
        title.text = option.label
        hint.text = option.hint
        hint.visibility = if (option.hint.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE

        fade(art, if (kind == Preview.NONE) ART_DIM else 1f, animate)
        fade(subtitles, if (kind == Preview.SUBTITLES && prefs.subtitleMode != SubtitleMode.OFF) 1f else 0f, animate)
        fade(sound, if (kind == Preview.SOUND) 1f else 0f, animate)
        if (kind == Preview.SUBTITLES) styleSubtitles()
        if (kind == Preview.SOUND) bars.show(prefs.nightMode, prefs.dialogueBoost, animate && shown == Preview.SOUND)
        shape(if (kind == Preview.SHAPE) prefs.resizeMode else AspectRatioFrameLayout.RESIZE_MODE_ZOOM, animate)
        shown = kind
    }

    /** The settings are opening again: the next scene appears in place. */
    fun reset() {
        shown = null
    }

    private fun styleSubtitles() {
        val style = PlayerPrefs.SUBTITLE_STYLES[prefs.subtitleStyle]
        subtitles.setStyle(CaptionStyleCompat(style.text, style.background, 0x00000000, style.edgeType, 0xFF000000.toInt(), fonts.semibold))
        subtitles.setFractionalTextSize(PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].fraction)
        subtitles.setBottomPaddingFraction(PlayerPrefs.SUBTITLE_POSITIONS[prefs.subtitlePosition].lift)
    }

    /** Fit shows the whole wide film with bars, Fill fills the frame and trims the sides, Stretch fills it out of shape. */
    private fun shape(mode: Int, animate: Boolean) {
        val fill = FILM_RATIO / SCREEN_RATIO
        val (x, y) = when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> 1f to 1f
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> 1f to fill
            else -> fill to fill
        }
        film.animate().cancel()
        if (animate) {
            film.animate().scaleX(x).scaleY(y).setDuration(GLIDE_MS).setInterpolator(Motion.enter).start()
        } else {
            film.scaleX = x
            film.scaleY = y
        }
    }

    private fun fade(view: View, alpha: Float, animate: Boolean) {
        view.animate().cancel()
        if (animate) view.animate().alpha(alpha).setDuration(GLIDE_MS).setInterpolator(Motion.enter).start() else view.alpha = alpha
    }

    private companion object {
        const val FILM_RATIO = 2.39f
        const val SCREEN_RATIO = 16f / 9f
        const val ART_DIM = 0.55f
        const val GLIDE_MS = 220L
    }
}
