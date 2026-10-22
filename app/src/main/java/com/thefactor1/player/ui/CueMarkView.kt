package com.thefactor1.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.thefactor1.player.R

/**
 * The pre-show's reel-change cue mark: a hairline ring in the corner that fills as the stream
 * buffers, then flashes twice as a solid dot when the film starts, the way a projectionist's cue
 * marks flash before a changeover. It only redraws while the ring is still catching up.
 */
class CueMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val stroke = 1.5f * resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ink
        alpha = 46
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ink
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val rect = RectF()
    private val handler = Handler(Looper.getMainLooper())

    private var target = 0f
    private var shown = 0f
    private var dotOn = false
    private var flashing = false

    /** How full the ring is, 0 to 1. It eases towards the value instead of jumping. */
    var progress: Float
        get() = target
        set(value) {
            target = value.coerceIn(0f, 1f)
            if (shown != target) postInvalidateOnAnimation()
        }

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        target = 0f
        shown = 0f
        dotOn = false
        flashing = false
        invalidate()
    }

    /** Two flashes of the dot, then [onDone]. The ring is gone from the first flash. */
    fun flash(onDone: () -> Unit) {
        handler.removeCallbacksAndMessages(null)
        flashing = true
        var at = 0L
        for ((on, length) in FLASHES) {
            handler.postDelayed({ dotOn = on; invalidate() }, at)
            at += length
        }
        handler.postDelayed({ dotOn = false; invalidate(); onDone() }, at)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - stroke
        if (flashing) {
            if (dotOn) canvas.drawCircle(cx, cy, radius * DOT_SCALE, dotPaint)
            return
        }
        shown += (target - shown) * EASE
        if (kotlin.math.abs(target - shown) < 0.002f) shown = target
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawOval(rect, trackPaint)
        if (shown > 0f) canvas.drawArc(rect, -90f, 360f * shown, false, arcPaint)
        if (shown != target) postInvalidateOnAnimation()
    }

    private companion object {
        const val EASE = 0.12f
        const val DOT_SCALE = 0.7f
        /** On, off, on: each pair is whether the dot shows and for how long, in ms. */
        val FLASHES = listOf(true to 150L, false to 130L, true to 150L)
    }
}
