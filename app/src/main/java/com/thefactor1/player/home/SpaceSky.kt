package com.thefactor1.player.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.thefactor1.player.R
import kotlin.random.Random

/**
 * The Space theme's sky: a night gradient into deep blue, with a planet's limb glowing along the
 * bottom. Drawn once per size; nothing in it moves.
 */
class SpaceSky(context: Context) : View(context) {
    private val sky = Paint()
    private val planet = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = context.getColor(R.color.space_accent)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sky.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), context.getColor(R.color.space_ground), context.getColor(R.color.space_deep), Shader.TileMode.CLAMP)
        val radius = w * LIMB_RADIUS
        val cy = h + radius - h * LIMB_RISE
        // The atmosphere: a soft band of light just outside the planet's edge.
        glow.shader = RadialGradient(w / 2f, cy, radius + h * 0.12f,
            intArrayOf(ColorUtils.setAlphaComponent(accent, 0), ColorUtils.setAlphaComponent(accent, 70), ColorUtils.setAlphaComponent(accent, 0)),
            floatArrayOf(radius / (radius + h * 0.12f) - 0.01f, radius / (radius + h * 0.12f), 1f), Shader.TileMode.CLAMP)
        planet.color = context.getColor(R.color.space_ground)
        rim.color = ColorUtils.setAlphaComponent(accent, 200)
        rim.strokeWidth = resources.displayMetrics.density * 1.2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, sky)
        val radius = w * LIMB_RADIUS
        val cy = h + radius - h * LIMB_RISE
        canvas.drawCircle(w / 2, cy, radius + h * 0.12f, glow)
        canvas.drawCircle(w / 2, cy, radius, planet)
        canvas.drawCircle(w / 2, cy, radius, rim)
    }

    private companion object {
        const val LIMB_RADIUS = 1.6f
        /** How much of the screen's height the planet's curve rises to at its middle. */
        const val LIMB_RISE = 0.07f
    }
}

/**
 * One layer of stars: drawn once into a bitmap a little wider than the screen, then only moved
 * (a slow horizontal drift on the GPU), so it costs next to nothing per frame.
 */
class StarLayer(context: Context, private val count: Int, private val minDp: Float, private val maxDp: Float,
                private val driftDp: Float, private val periodMs: Long, private val seed: Int) : View(context) {
    private var stars: Bitmap? = null
    private var drift: ObjectAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val density = resources.displayMetrics.density
        val extra = (driftDp * density).toInt()
        // [w] already includes the drift (see onMeasure).
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(seed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val white = context.getColor(R.color.space_fg)
        repeat(count) {
            paint.color = ColorUtils.setAlphaComponent(white, (90 + random.nextInt(150)))
            val r = (minDp + random.nextFloat() * (maxDp - minDp)) * density / 2
            canvas.drawCircle(random.nextFloat() * bitmap.width, random.nextFloat() * h, r, paint)
        }
        stars?.recycle()
        stars = bitmap
        drift?.cancel()
        drift = ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -extra.toFloat()).apply {
            duration = periodMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        stars?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As wide as the screen plus the drift, so moving it never shows an edge.
        val extra = (driftDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec) + extra, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drift?.cancel()
    }
}
