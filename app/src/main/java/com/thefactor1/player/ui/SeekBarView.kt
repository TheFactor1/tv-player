package com.thefactor1.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.thefactor1.player.R

/**
 * A thin seek bar for the remote: Left and Right scrub while it has focus, and it thickens with a
 * thumb to show it does. Draws with preallocated paints, so a redraw allocates nothing.
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called with how far to jump, in ms; repeats of a held key ask for longer jumps. */
    var onScrub: ((deltaMs: Long) -> Unit)? = null

    private var durationMs = 0L
    private var positionMs = 0L
    private var bufferedMs = 0L

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 72
    }
    private val rect = RectF()

    // 0 = resting (thin, no thumb), 1 = focused (thick, thumb shown).
    private var focusAmount = 0f
    private val focusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 160
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            focusAmount = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isFocusable = true
    }

    fun update(positionMs: Long, bufferedMs: Long, durationMs: Long) {
        if (positionMs == this.positionMs && bufferedMs == this.bufferedMs && durationMs == this.durationMs) return
        this.positionMs = positionMs
        this.bufferedMs = bufferedMs
        this.durationMs = durationMs
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) focusAnimator.start() else focusAnimator.reverse()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        val step = when {
            event.repeatCount >= 30 -> 120_000L
            event.repeatCount >= 10 -> 30_000L
            else -> 10_000L
        }
        onScrub?.invoke(direction * step)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val half = (2f + 2f * focusAmount) * density
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val span = right - left

        rect.set(left, cy - half, right, cy + half)
        canvas.drawRoundRect(rect, half, half, trackPaint)
        if (durationMs <= 0) return

        val bufferedX = left + span * (bufferedMs.coerceIn(0, durationMs) / durationMs.toFloat())
        rect.set(left, cy - half, bufferedX, cy + half)
        canvas.drawRoundRect(rect, half, half, bufferPaint)

        val progressX = left + span * (positionMs.coerceIn(0, durationMs) / durationMs.toFloat())
        rect.set(left, cy - half, progressX, cy + half)
        canvas.drawRoundRect(rect, half, half, progressPaint)

        if (focusAmount > 0f) {
            val radius = 9f * density * focusAmount
            canvas.drawCircle(progressX, cy, radius + 4f * density, haloPaint)
            canvas.drawCircle(progressX, cy, radius, thumbPaint)
        }
    }
}
