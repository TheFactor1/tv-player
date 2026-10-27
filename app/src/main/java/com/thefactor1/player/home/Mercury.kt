package com.thefactor1.player.home

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion

/**
 * The Home's look in code: its text styles, the backdrop's fade, and a title's credits line. Mercury
 * by default; [space] swaps in the Space theme's palette and display face (same layout).
 */
object Mercury {
    /** The Space theme is on; set by Home before it builds anything. */
    var space = false

    /** A colour resource as the current theme draws it: Space is colder (star-white text, ice-blue accent). */
    fun color(context: Context, id: Int): Int = context.getColor(
        if (!space) id else when (id) {
            R.color.mercury_fg -> R.color.space_fg
            R.color.mercury_muted -> R.color.space_muted
            R.color.mercury_blue -> R.color.space_accent
            R.color.ground -> R.color.space_ground
            else -> id
        },
    )

    /** The meniscus under an active tab or choice, in the theme's accent. */
    fun meniscus(context: Context): Drawable? =
        context.getDrawable(R.drawable.mercury_meniscus)?.mutate()?.apply { if (space) setTint(color(context, R.color.mercury_blue)) }

    /** Text in the display face: Bricolage Grotesque drawn tight, or Space's Orbitron, wider, set a size down and tracked open. */
    fun title(context: Context, fonts: Fonts, sizeSp: Float, mercuryTracking: Float = -0.03f): TextView =
        text(context, if (space) fonts.spaceDisplay else fonts.display, displaySize(sizeSp), tracking = if (space) SPACE_TRACKING else mercuryTracking)

    fun displaySize(sizeSp: Float) = if (space) sizeSp * SPACE_SCALE else sizeSp

    private const val SPACE_TRACKING = 0.05f
    /** Orbitron runs wide; this keeps titles at the same visual weight as Mercury's. */
    private const val SPACE_SCALE = 0.8f

    /** Unfocused rows and tabs; the prototype's 55 % and 38 %. */
    const val TAB_REST = 0.55f
    const val ROW_REST = 0.38f
    const val CONTROL_REST = 0.7f
    /** The TV safe area (res/values/dimens.xml): edges a projector or older TV may crop. */
    const val SAFE_X = 48f
    const val SAFE_Y = 27f
    /** A glass sheet's share of the screen width, from the right edge. */
    const val SHEET_WIDTH = 0.38f
    private const val BLUR_DP = 22f
    /** The backdrop's crossfade between titles. */
    const val ART_FADE_MS = 600L

    fun text(context: Context, face: Typeface, sizeSp: Float, color: Int = R.color.mercury_fg, tracking: Float = 0f): TextView =
        TextView(context).apply {
            typeface = face
            textSize = sizeSp
            letterSpacing = tracking
            includeFontPadding = false
            setTextColor(color(context, color))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun dp(view: View, value: Float): Int = (value * view.resources.displayMetrics.density).toInt()

    /**
     * Makes [root] a glass sheet: the backdrop blurred behind it (lined up with the full-screen one,
     * so it reads as glass over it; Android 12 and up), a tint, and the one chrome line along the
     * top. Returns the blurred layer, for the caller to give the backdrop on screen.
     */
    fun glass(root: FrameLayout): ImageView {
        val context = root.context
        val screenWidth = root.resources.displayMetrics.widthPixels
        root.layoutParams.width = (screenWidth * SHEET_WIDTH).toInt()
        val glass = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(glass, FrameLayout.LayoutParams(screenWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        glass.translationX = -(screenWidth - root.layoutParams.width).toFloat()
        val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (blur) {
            val radius = dp(root, BLUR_DP).toFloat()
            glass.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        } else {
            glass.visibility = View.GONE
        }
        root.addView(View(context).apply { setBackgroundColor(context.getColor(if (blur) R.color.mercury_glass else R.color.mercury_glass_plain)) })
        root.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.mercury_chrome)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(root, 1f)))
        return glass
    }

    /** A sheet arriving from the right: a short slide and a fade, no bounce. */
    fun slideIn(sheet: View) {
        sheet.animate().cancel()
        sheet.visibility = View.VISIBLE
        sheet.alpha = 0f
        sheet.translationX = dp(sheet, Motion.RISE_DP * 3).toFloat()
        sheet.animate().alpha(1f).translationX(0f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).setListener(null).start()
    }

    /** The way it came, a third quicker. */
    fun slideOut(sheet: View) {
        sheet.animate().cancel()
        sheet.animate().alpha(0f).translationX(dp(sheet, Motion.RISE_DP * 3).toFloat())
            .setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit)
            .withEndAction { sheet.visibility = View.INVISIBLE }.start()
    }

    /**
     * The long fade that keeps the list on darkness: solid ground for the left quarter, clear by three
     * quarters across, plus a light fade from the top and bottom edges.
     */
    fun backdropFade(context: Context): Drawable {
        val ground = color(context, R.color.ground)
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

    /** "2023 · 1 H 55 MIN · MUSICAL · 4K · ATMOS", plus "IN YOUR LIBRARY" where it applies. */
    fun credits(item: HubItem, showLibrary: Boolean): String {
        val runtime = item.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} H ${it % 60} MIN" else "$it MIN" }
        val library = if (showLibrary && item.inLibrary) "IN YOUR LIBRARY" else null
        return listOfNotNull(item.year?.toString(), runtime, item.genres.firstOrNull(), *item.badges.all().toTypedArray(), library)
            .joinToString(" · ")
            .uppercase()
    }

    /**
     * Where you are in a title, the same words wherever it appears: "S02E03 · 42 MIN LEFT",
     * "42 MIN LEFT", or "NEXT · S02E03" for a show not yet started on that episode.
     */
    fun status(item: HubItem): String? {
        val resume = item.resume?.takeIf { it.durationMs > 0 && it.positionMs > 0 }
        val left = resume?.let { ((it.durationMs - it.positionMs) / 60_000).coerceAtLeast(1) }
        val episode = item.nextEpisode?.let { String.format(java.util.Locale.US, "S%02dE%02d", it.season, it.episode) }
        return when {
            episode != null && left != null -> "$episode · $left MIN LEFT"
            episode != null -> "NEXT · $episode"
            left != null -> "$left MIN LEFT"
            else -> null
        }
    }

    /** How far into [item] the shared resume point is, 0–1, or null when there isn't one. */
    fun progress(item: HubItem): Float? =
        item.resume?.takeIf { it.durationMs > 0 && it.positionMs > 0 }?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
}
