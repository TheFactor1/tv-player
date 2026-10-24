package com.thefactor1.player.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.thefactor1.player.companion.BeaconPattern
import com.thefactor1.player.companion.BeaconScreen
import java.lang.ref.WeakReference

/**
 * The invisible pairing code (a prototype): the code's grid over the whole picture, each cell a
 * shade brighter on one screen refresh and the same shade darker on the next. The eye averages each
 * pair of refreshes into nothing; a phone camera with a short shutter catches one or the other. It
 * shows only while the phone's scanner asks for it, and only at 50 Hz or more, since at 24 Hz the
 * flicker would be plain to see.
 */
object Beacon : BeaconScreen {
    private const val MIN_REFRESH_HZ = 48f
    private const val RAMP_MS = 400L
    private const val MAX_STRENGTH = 24

    private val handler = Handler(Looper.getMainLooper())
    private var screen: WeakReference<Activity>? = null
    private var dialog: Dialog? = null
    private val hideLater = Runnable { hideBeacon() }

    /** Called from each screen's onStart. */
    fun attach(activity: Activity) {
        screen = WeakReference(activity)
    }

    /** Called from each screen's onStop. */
    fun detach(activity: Activity) {
        if (screen?.get() !== activity) return
        hideBeacon()
        screen = null
    }

    override fun showBeacon(cells: BooleanArray, strength: Int, seconds: Int): Float? {
        val activity = screen?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return null
        @Suppress("DEPRECATION")
        val refresh = activity.windowManager.defaultDisplay.refreshRate
        if (refresh < MIN_REFRESH_HZ) return -refresh
        handler.removeCallbacks(hideLater)
        val view = (dialog?.takeIf { it.isShowing }?.window?.decorView?.findViewWithTag<BeaconView>(TAG))
            ?: open(activity)
        view.update(cells, strength.coerceIn(1, MAX_STRENGTH))
        handler.postDelayed(hideLater, seconds * 1000L)
        return refresh
    }

    override fun hideBeacon() {
        handler.removeCallbacks(hideLater)
        dialog?.let { if (it.isShowing) it.dismiss() }
        dialog = null
    }

    private fun open(activity: Activity): BeaconView {
        hideBeacon()
        val view = BeaconView(activity).apply { tag = TAG }
        val window = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        window.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Never takes the remote or a touch: the film's controls carry on underneath.
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
        }
        window.show()
        dialog = window
        return view
    }

    private const val TAG = "beacon"

    private class BeaconView(context: Context) : View(context), Choreographer.FrameCallback {
        private val paint = Paint()
        private var cells = BooleanArray(BeaconPattern.COLS * BeaconPattern.ROWS)
        private var strength = 0
        private var startedAt = 0L
        private var flipped = false
        private var periodNs = 16_683_350L

        fun update(cells: BooleanArray, strength: Int) {
            if (this.strength == 0) startedAt = SystemClock.uptimeMillis()
            this.cells = cells
            this.strength = strength
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            @Suppress("DEPRECATION")
            display?.refreshRate?.takeIf { it > 0 }?.let { periodNs = (1e9 / it).toLong() }
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDetachedFromWindow() {
            Choreographer.getInstance().removeFrameCallback(this)
            super.onDetachedFromWindow()
        }

        override fun doFrame(frameTimeNanos: Long) {
            // From the clock rather than a count, so a missed refresh doesn't swap the two halves for good.
            flipped = (frameTimeNanos / periodNs) % 2 == 1L
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDraw(canvas: Canvas) {
            if (strength == 0) return
            // Eases in, so the first refreshes aren't a lopsided step.
            val ramp = ((SystemClock.uptimeMillis() - startedAt).toFloat() / RAMP_MS).coerceIn(0f, 1f)
            val alpha = (strength * ramp).toInt().coerceAtLeast(if (ramp > 0f) 1 else 0)
            val cellWidth = width.toFloat() / BeaconPattern.COLS
            val cellHeight = height.toFloat() / BeaconPattern.ROWS
            for (row in 0 until BeaconPattern.ROWS) {
                for (col in 0 until BeaconPattern.COLS) {
                    val on = cells[row * BeaconPattern.COLS + col] != flipped
                    paint.color = if (on) Color.WHITE else Color.BLACK
                    paint.alpha = alpha
                    canvas.drawRect(col * cellWidth, row * cellHeight, (col + 1) * cellWidth, (row + 1) * cellHeight, paint)
                }
            }
        }
    }
}
