package com.thefactor1.player.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.thefactor1.player.R

/** Manrope weights, and IBM Plex Mono for the pre-show log, loaded once. Loaded in code so they also work before Android 8. */
class Fonts(context: Context) {
    val medium: Typeface = ResourcesCompat.getFont(context, R.font.manrope_medium) ?: Typeface.DEFAULT
    val semibold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_semibold) ?: Typeface.DEFAULT_BOLD
    val bold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_bold) ?: Typeface.DEFAULT_BOLD
    val mono: Typeface = ResourcesCompat.getFont(context, R.font.plex_mono) ?: Typeface.MONOSPACE

    fun apply(typeface: Typeface, vararg views: TextView) = views.forEach { it.typeface = typeface }
}
