package com.thefactor1.player.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import com.thefactor1.player.R
import com.thefactor1.player.meta.SkipSegment
import com.thefactor1.player.meta.SkipSegments

/**
 * The "Skip intro" button, Netflix style: it appears in the corner when a recap, intro or the
 * credits start, OK takes it, and it fades after a few seconds so the press goes back to pausing.
 * Skipping the credits ends the video, which returns to the source app marked as watched.
 *
 * It only checks the position twice a second while playing, and only when there are skip times.
 */
class SkipPrompt(private val button: TextView, private val onSkipCredits: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var segments: List<SkipSegment> = emptyList()
    private var current: SkipSegment? = null
    private var enteredAt = 0L

    /** True while something else owns the screen (controls, a panel, the pause screen), which hides the button. */
    var blocked = false
        set(value) {
            field = value
            refresh()
        }

    val isShowing: Boolean get() = button.visibility == View.VISIBLE && button.alpha > 0f

    fun attach(player: Player) {
        this.player = player
        refresh()
    }

    fun detach() {
        player = null
        handler.removeCallbacks(tick)
        hide(animate = false)
    }

    /** New skip times, or none for a new video. */
    fun setSegments(segments: List<SkipSegment>) {
        this.segments = segments
        current = null
        refresh()
    }

    /** OK while the button shows: skips and returns true. */
    fun press(): Boolean {
        val segment = current ?: return false
        if (!isShowing) return false
        val exo = player ?: return false
        hide(animate = true)
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val end = SkipSegments.endOf(segment, duration)
        if (segment.kind == SkipSegment.Kind.CREDITS && (segment.endMs == null || end >= duration - CREDITS_TAIL_MS)) {
            onSkipCredits()
        } else {
            exo.seekTo(end)
        }
        // Don't offer the same segment again on the next tick.
        enteredAt = 0L
        return true
    }

    /** Re-checks at once, e.g. after a seek or when the controls hide. */
    fun refresh() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    private val tick = object : Runnable {
        override fun run() {
            val exo = player
            if (exo == null || segments.isEmpty()) {
                hide(animate = false)
                return
            }
            val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val segment = SkipSegments.at(SkipSegments.usable(segments, duration), exo.currentPosition, duration)
            if (segment != current) {
                current = segment
                enteredAt = SystemClock.uptimeMillis()
            }
            val inWindow = segment != null && enteredAt > 0 && SystemClock.uptimeMillis() - enteredAt < PROMPT_MS
            if (segment != null && inWindow && !blocked && exo.playWhenReady) show(segment) else hide(animate = true)
            if (exo.playWhenReady) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun show(segment: SkipSegment) {
        button.setText(
            when (segment.kind) {
                SkipSegment.Kind.RECAP -> R.string.skip_recap
                SkipSegment.Kind.INTRO -> R.string.skip_intro
                SkipSegment.Kind.CREDITS -> R.string.skip_credits
            },
        )
        if (button.visibility == View.VISIBLE && button.alpha == 1f) return
        button.animate().cancel()
        if (button.visibility != View.VISIBLE) {
            button.alpha = 0f
            button.translationY = 12f * button.resources.displayMetrics.density
            button.visibility = View.VISIBLE
        }
        button.animate().alpha(1f).translationY(0f).setDuration(SHOW_MS).start()
    }

    private fun hide(animate: Boolean) {
        if (button.visibility != View.VISIBLE) return
        button.animate().cancel()
        if (animate) {
            button.animate().alpha(0f).setDuration(HIDE_MS).withEndAction { button.visibility = View.INVISIBLE }.start()
        } else {
            button.alpha = 0f
            button.visibility = View.INVISIBLE
        }
    }

    private companion object {
        const val TICK_MS = 500L
        const val PROMPT_MS = 10_000L
        const val SHOW_MS = 240L
        const val HIDE_MS = 220L
        /** Credits that end this close to the end of the video are the end of it. */
        const val CREDITS_TAIL_MS = 30_000L
    }
}
