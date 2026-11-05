package com.thefactor1.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A thin seek bar for the remote: Left and Right scrub while it has focus, and it thickens with a
 * thumb to show it does. On a touchscreen ([onDragEnd] set) it is dragged instead, thickening while
 * held. Draws with preallocated paints, so a redraw allocates nothing.
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called with how far to jump, in ms; repeats of a held key ask for longer jumps. */
    var onScrub: ((deltaMs: Long) -> Unit)? = null

    /** Touch: the spot under the finger while it's held, in ms. */
    var onDragMove: ((positionMs: Long) -> Unit)? = null
    /** Touch: where the finger let go, or null when the drag was taken away (nothing to seek to). */
    var onDragEnd: ((positionMs: Long?) -> Unit)? = null

    /** A finger is on the bar; the player's own position waits until it lets go. */
    var isDragging = false
        private set
    private var positionBeforeDrag = 0L

    private var durationMs = 0L
    private var positionMs = 0L
    private var bufferedMs = 0L

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    // Chapter starts are small notches cut into the bar, darker than either side of it.
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private var marksMs = LongArray(0)
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

    /** Where chapters start, in ms; the first, at 0, has no notch. */
    fun setMarks(marksMs: LongArray) {
        if (this.marksMs.contentEquals(marksMs)) return
        this.marksMs = marksMs
        invalidate()
    }

    fun update(positionMs: Long, bufferedMs: Long, durationMs: Long) {
        if (isDragging) {
            this.bufferedMs = bufferedMs
            this.durationMs = durationMs
            invalidate()
            return
        }
        if (positionMs == this.positionMs && bufferedMs == this.bufferedMs && durationMs == this.durationMs) return
        this.positionMs = positionMs
        this.bufferedMs = bufferedMs
        this.durationMs = durationMs
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateFocus(if (gainFocus || isDragging) 1f else 0f)
    }

    // From wherever it is now, so focus passing quickly back and forth never snaps the bar thin.
    private fun animateFocus(target: Float) {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val end = onDragEnd ?: return super.onTouchEvent(event)
        if (durationMs <= 0 && !isDragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                positionBeforeDrag = positionMs
                animateFocus(1f)
                dragTo(event.x)
            }
            MotionEvent.ACTION_MOVE -> if (isDragging) dragTo(event.x)
            MotionEvent.ACTION_UP -> if (isDragging) {
                dragTo(event.x)
                isDragging = false
                animateFocus(if (isFocused) 1f else 0f)
                performClick()
                end(positionMs)
            }
            MotionEvent.ACTION_CANCEL -> if (isDragging) {
                isDragging = false
                positionMs = positionBeforeDrag
                animateFocus(if (isFocused) 1f else 0f)
                end(null)
            }
        }
        return true
    }

    private fun dragTo(x: Float) {
        val left = paddingLeft + thumbReach
        val span = (width - paddingRight - thumbReach - left).coerceAtLeast(1f)
        positionMs = (((x - left) / span).coerceIn(0f, 1f) * durationMs).toLong()
        invalidate()
        onDragMove?.invoke(positionMs)
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

        val gap = MARK_GAP_DP * density / 2f
        for (mark in marksMs) {
            if (mark <= 0 || mark >= durationMs) continue
            val x = left + span * (mark / durationMs.toFloat())
            canvas.drawRect(x - gap, cy - half, x + gap, cy + half, markPaint)
        }

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
        const val MARK_GAP_DP = 2f
    }
}
