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
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val rect = RectF()
    private val thumbReach = (THUMB_RADIUS_DP + HALO_DP) * density

    // 0 = resting (thin, no thumb), 1 = focused (thick, thumb shown).
    private var focusAmount = 0f
    private var focusAnimator: ValueAnimator? = null

    init {
        isFocusable = true
    }

    /** The played part's colour: white, or the film's own accent. The thumb stays white. */
    fun setProgressColor(color: Int) {
        if (progressPaint.color == color) return
        progressPaint.color = color
        invalidate()
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
        // From wherever it is now, so focus passing quickly back and forth never snaps the bar thin.
        val target = if (gainFocus) 1f else 0f
        focusAnimator?.cancel()
        focusAnimator = ValueAnimator.ofFloat(focusAmount, target).apply {
            duration = (FOCUS_MS * kotlin.math.abs(target - focusAmount)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                focusAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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
        // Inset by the focused thumb's halo so the thumb is never clipped at either end.
        val left = paddingLeft + thumbReach
        val right = width - paddingRight - thumbReach
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
            val radius = THUMB_RADIUS_DP * density * focusAmount
            canvas.drawCircle(progressX, cy, radius + HALO_DP * density, haloPaint)
            canvas.drawCircle(progressX, cy, radius, thumbPaint)
        }
    }

    private companion object {
        const val THUMB_RADIUS_DP = 9f
        const val HALO_DP = 4f
        const val FOCUS_MS = 160f
    }
}
