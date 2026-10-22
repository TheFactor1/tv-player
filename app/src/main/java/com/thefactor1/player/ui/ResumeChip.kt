package com.thefactor1.player.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.thefactor1.player.R

/**
 * A quiet note after a resume, "Resumed at 42:10 · OK twice to start over", that fades out by itself.
 * It never blocks playback: OK twice while it shows starts over, any other key dismisses it and carries on.
 */
class ResumeChip(activity: Activity, fonts: Fonts) {
    private val chip: View = LayoutInflater.from(activity)
        .inflate(R.layout.view_resume_chip, activity.findViewById<ViewGroup>(R.id.root), false)
        .also { activity.findViewById<ViewGroup>(R.id.root).addView(it) }
    private val label: TextView = chip.findViewById(R.id.resume_chip_label)
    private val key: TextView = chip.findViewById(R.id.resume_chip_key)
    private val action: TextView = chip.findViewById(R.id.resume_chip_action)
    private val handler = Handler(Looper.getMainLooper())
    private val easeOut = DecelerateInterpolator(2f)

    init {
        fonts.apply(fonts.semibold, label)
        fonts.apply(fonts.medium, action)
        fonts.apply(fonts.bold, key)
    }

    val isShowing: Boolean get() = chip.visibility == View.VISIBLE

    fun show(text: String) {
        label.text = text
        key.visibility = View.VISIBLE
        action.visibility = View.VISIBLE
        chip.animate().cancel()
        chip.alpha = 0f
        chip.translationY = 24f
        chip.visibility = View.VISIBLE
        chip.animate().alpha(1f).translationY(0f).setDuration(SHOW_MS).setInterpolator(easeOut).start()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, VISIBLE_MS)
    }

    /** Confirms the start-over choice for a moment before fading. */
    fun confirm(text: String) {
        label.text = text
        key.visibility = View.GONE
        action.visibility = View.GONE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, CONFIRM_MS)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (!isShowing) return
        chip.animate().cancel()
        chip.animate().alpha(0f).setDuration(HIDE_MS).withEndAction { chip.visibility = View.GONE }.start()
    }

    private val hideRunnable = Runnable { hide() }

    companion object {
        private const val SHOW_MS = 260L
        private const val HIDE_MS = 320L
        private const val VISIBLE_MS = 8_000L
        private const val CONFIRM_MS = 1_200L

        /** "42:10" or "1:02:05". */
        fun formatTime(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
