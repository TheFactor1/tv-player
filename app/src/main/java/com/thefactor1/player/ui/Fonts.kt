package com.thefactor1.player.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.thefactor1.player.R
import com.thefactor1.player.home.Mercury

/**
 * Manrope weights, and IBM Plex Mono for the pre-show log, loaded once. Loaded in code so they also work before Android 8.
 * The Home's Mercury faces are instances of variable fonts; before Android 9 they draw at the font's default weight.
 * Under Broadsheet (Mercury.paper) the same names give its newspaper faces: Playfair Display and Source Serif 4.
 */
class Fonts(context: Context) {
    val medium: Typeface = ResourcesCompat.getFont(context, R.font.manrope_medium) ?: Typeface.DEFAULT
    val semibold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_semibold) ?: Typeface.DEFAULT_BOLD
    val bold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_bold) ?: Typeface.DEFAULT_BOLD
    val mono: Typeface = ResourcesCompat.getFont(context, R.font.plex_mono) ?: Typeface.MONOSPACE
    private val paper = Mercury.paper
    val display: Typeface by lazy { ResourcesCompat.getFont(context, if (paper) R.font.paper_display else R.font.mercury_display) ?: bold }
    val ui: Typeface by lazy { ResourcesCompat.getFont(context, if (paper) R.font.paper_ui else R.font.mercury_ui) ?: medium }
    val uiBold: Typeface by lazy { ResourcesCompat.getFont(context, if (paper) R.font.paper_ui_bold else R.font.mercury_ui_bold) ?: semibold }
    val spaceDisplay: Typeface by lazy { ResourcesCompat.getFont(context, R.font.space_display) ?: display }
    val mercuryMono: Typeface by lazy { ResourcesCompat.getFont(context, if (paper) R.font.paper_label else R.font.mercury_mono) ?: mono }

    fun apply(typeface: Typeface, vararg views: TextView) = views.forEach { it.typeface = typeface }
}
