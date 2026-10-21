package com.thefactor1.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.thefactor1.player.R

/**
 * The loading indicator: a hairline with a soft highlight sweeping along it. It animates only
 * while it can be seen, and a frame moves a shader matrix, so drawing allocates nothing.
 */
class SweepLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 31 }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private val rect = RectF()
    private var sweepWidth = 0f
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sweepWidth = w * 0.4f
        val clear = ink and 0x00FFFFFF
        sweepPaint.shader = LinearGradient(
            0f, 0f, sweepWidth, 0f,
            intArrayOf(clear, ink, clear), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        // The highlight enters from beyond the left end and leaves beyond the right.
        shaderMatrix.setTranslate(-sweepWidth + (width + sweepWidth) * phase, 0f)
        sweepPaint.shader?.setLocalMatrix(shaderMatrix)
        canvas.drawRoundRect(rect, radius, radius, sweepPaint)
    }
}
