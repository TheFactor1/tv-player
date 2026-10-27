package com.thefactor1.player.home

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/** A small turning arc for a button's "Starting…": the press was felt, the work is under way. */
class SpinnerDrawable(color: Int, private val sizePx: Int, strokePx: Float) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private val turn = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = TURN_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidateSelf() }
    }

    override fun draw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2
        oval.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        canvas.drawArc(oval, turn.animatedValue as Float, SWEEP, false, paint)
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun start() = turn.start()
    override fun stop() = turn.cancel()
    override fun isRunning() = turn.isRunning

    private companion object {
        const val TURN_MS = 900L
        const val SWEEP = 270f
    }
}
