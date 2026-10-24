package com.thefactor1.player.ui

import android.animation.ValueAnimator
import android.view.View

/**
 * One focus highlight that glides from control to control instead of each control lighting up
 * on its own. [highlight] sits behind the controls in a parent that covers them; [paint] colours
 * a control for how focused it is (0 to 1), so its text or icon turns dark as the highlight
 * arrives under it and light as it leaves.
 */
class FocusGlide(private val highlight: View, private val paint: (View, Float) -> Unit) {
    private var current: View? = null
    private var move: ValueAnimator? = null

    /** The control the highlight is on, or null while it's away. */
    val target: View? get() = current

    /**
     * Moves the highlight to [target]. From nowhere it fades in where it lands; [animate] false
     * lands it at once (a list opening, the controls coming back).
     */
    fun moveTo(target: View, animate: Boolean = true) {
        if (!target.isLaidOut) {
            // A list just built has no positions yet; land on the next frame.
            current?.takeIf { it !== target }?.let { paint(it, 0f) }
            current = target
            target.post { if (current === target) place(target, animate = false, force = true) }
            return
        }
        place(target, animate, force = false)
    }

    private fun place(target: View, animate: Boolean, force: Boolean) {
        val previous = current
        if (!force && previous === target && highlight.visibility == View.VISIBLE && highlight.alpha == 1f) return
        current = target
        val (x, y) = offsetOf(target)
        val params = highlight.layoutParams
        if (params.width != target.width || params.height != target.height) {
            params.width = target.width
            params.height = target.height
            highlight.layoutParams = params
        }
        move?.cancel()
        highlight.animate().cancel()
        val shown = highlight.visibility == View.VISIBLE
        val glide = animate && shown && previous != null && previous !== target
        if (!glide) {
            highlight.translationX = x
            highlight.translationY = y
            if (previous != null && previous !== target) paint(previous, 0f)
            highlight.visibility = View.VISIBLE
            if (!animate) {
                highlight.alpha = 1f
                paint(target, 1f)
                return
            }
            // From nowhere: no glide in from a stale spot, just a quick fade where it lands.
            val from = if (shown) highlight.alpha else 0f
            move = animator { t ->
                val amount = from + (1f - from) * t
                highlight.alpha = amount
                paint(target, amount)
            }
            return
        }
        highlight.alpha = 1f
        val fromX = highlight.translationX
        val fromY = highlight.translationY
        move = animator { t ->
            highlight.translationX = fromX + (x - fromX) * t
            highlight.translationY = fromY + (y - fromY) * t
            paint(previous!!, 1f - t)
            paint(target, t)
        }
    }

    /** Fades the highlight away where it is (focus left for somewhere it doesn't go). */
    fun release() {
        val last = current ?: return
        current = null
        move?.cancel()
        if (highlight.visibility != View.VISIBLE) return
        val from = highlight.alpha
        move = animator { t ->
            val amount = from * (1f - t)
            highlight.alpha = amount
            paint(last, amount)
            if (t == 1f) highlight.visibility = View.INVISIBLE
        }
    }

    /** Takes the highlight away at once, e.g. when the controls have hidden; the next focus fades in. */
    fun clear() {
        move?.cancel()
        current?.let { paint(it, 0f) }
        current = null
        highlight.visibility = View.INVISIBLE
        highlight.alpha = 1f
    }

    private fun animator(step: (Float) -> Unit) = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Motion.FOCUS_MS
        interpolator = Motion.enter
        addUpdateListener { step(it.animatedValue as Float) }
        start()
    }

    /** [target]'s position relative to the highlight's parent, whatever lies between them. */
    private fun offsetOf(target: View): Pair<Float, Float> {
        val parent = highlight.parent as View
        var x = 0f
        var y = 0f
        var view: View = target
        while (view !== parent) {
            x += view.left + view.translationX
            y += view.top + view.translationY
            val up = view.parent as? View ?: break
            if (up !== parent) {
                x -= up.scrollX
                y -= up.scrollY
            }
            view = up
        }
        return x to y
    }
}
