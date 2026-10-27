package com.thefactor1.player.home

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem

/** The Home's Mercury look in code: its text styles, the backdrop's fade, and a title's credits line. */
object Mercury {
    /** Unfocused rows and tabs; the prototype's 55 % and 38 %. */
    const val TAB_REST = 0.55f
    const val ROW_REST = 0.38f
    const val CONTROL_REST = 0.7f
    /** The TV safe area (res/values/dimens.xml): edges a projector or older TV may crop. */
    const val SAFE_X = 48f
    const val SAFE_Y = 27f
    /** The backdrop's crossfade between titles. */
    const val ART_FADE_MS = 600L

    fun text(context: Context, face: Typeface, sizeSp: Float, color: Int = R.color.mercury_fg, tracking: Float = 0f): TextView =
        TextView(context).apply {
            typeface = face
            textSize = sizeSp
            letterSpacing = tracking
            includeFontPadding = false
            setTextColor(context.getColor(color))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun dp(view: View, value: Float): Int = (value * view.resources.displayMetrics.density).toInt()

    /**
     * The long fade that keeps the list on darkness: solid ground for the left quarter, clear by three
     * quarters across, plus a light fade from the top and bottom edges.
     */
    fun backdropFade(context: Context): Drawable {
        val ground = context.getColor(R.color.ground)
        fun fade(alongX: Boolean, reverse: Boolean, colors: IntArray, stops: FloatArray) = PaintDrawable().apply {
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader {
                    val end = (if (alongX) width else height).toFloat()
                    val (from, to) = if (reverse) end to 0f else 0f to end
                    return if (alongX) LinearGradient(from, 0f, to, 0f, colors, stops, Shader.TileMode.CLAMP)
                    else LinearGradient(0f, from, 0f, to, colors, stops, Shader.TileMode.CLAMP)
                }
            }
        }
        fun ground(alpha: Float) = ColorUtils.setAlphaComponent(ground, (alpha * 255).toInt())
        return LayerDrawable(
            arrayOf(
                fade(true, false, intArrayOf(ground, ground, ground(0.75f), ground(0f)), floatArrayOf(0f, 0.28f, 0.46f, 0.74f)),
                fade(false, false, intArrayOf(ground(0.8f), ground(0f)), floatArrayOf(0f, 0.26f)),
                fade(false, true, intArrayOf(ground(0.55f), ground(0f)), floatArrayOf(0f, 0.34f)),
            ),
        )
    }

    /** "2023 · 1 H 55 MIN · MUSICAL · 4K · ATMOS", plus the next episode or "IN YOUR LIBRARY" where they apply. */
    fun credits(item: HubItem, showLibrary: Boolean): String {
        val runtime = item.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} H ${it % 60} MIN" else "$it MIN" }
        val next = item.nextEpisode?.let { listOfNotNull("S${it.season} E${it.episode}", it.title).joinToString(" ") }
        val library = if (showLibrary && item.inLibrary) "IN YOUR LIBRARY" else null
        return listOfNotNull(next, item.year?.toString(), runtime, item.genres.firstOrNull(), *item.badges.all().toTypedArray(), library)
            .joinToString(" · ")
            .uppercase()
    }
}
