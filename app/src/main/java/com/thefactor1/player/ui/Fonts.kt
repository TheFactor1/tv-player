package com.thefactor1.player.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.thefactor1.player.R

/**
 * Manrope weights, and IBM Plex Mono for the pre-show log, loaded once. Loaded in code so they also work before Android 8.
 * The Home's Mercury faces are instances of variable fonts; before Android 9 they draw at the font's default weight.
 */
class Fonts(context: Context) {
    val medium: Typeface = ResourcesCompat.getFont(context, R.font.manrope_medium) ?: Typeface.DEFAULT
    val semibold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_semibold) ?: Typeface.DEFAULT_BOLD
    val bold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_bold) ?: Typeface.DEFAULT_BOLD
    val mono: Typeface = ResourcesCompat.getFont(context, R.font.plex_mono) ?: Typeface.MONOSPACE
    val display: Typeface by lazy { ResourcesCompat.getFont(context, R.font.mercury_display) ?: bold }
    val ui: Typeface by lazy { ResourcesCompat.getFont(context, R.font.mercury_ui) ?: medium }
    val uiBold: Typeface by lazy { ResourcesCompat.getFont(context, R.font.mercury_ui_bold) ?: semibold }
    val mercuryMono: Typeface by lazy { ResourcesCompat.getFont(context, R.font.mercury_mono) ?: mono }

    fun apply(typeface: Typeface, vararg views: TextView) = views.forEach { it.typeface = typeface }
}
