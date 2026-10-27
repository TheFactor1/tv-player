package com.thefactor1.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.thefactor1.player.R
import kotlin.math.abs
import kotlin.math.sin

/**
 * A still of a film's sound as a row of level bars, for the settings preview: a loud stretch in
 * the middle, voices every few bars. Night mode squeezes loud and quiet together; dialogue boost
 * lifts the voices out. Changes glide between the two pictures rather than jumping.
 */
class SoundBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val voiceColor = context.getColor(R.color.ink)
    private val restColor = ColorUtils.setAlphaComponent(voiceColor, REST_ALPHA)

    private val levels = FloatArray(COUNT) { level(it, night = false) }
    private var from = levels.copyOf()
    private var to = levels.copyOf()
    private var voiceLift = 0f
    private var voiceFrom = 0f
    private var voiceTo = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = GLIDE_MS
        interpolator = Motion.enter
        addUpdateListener {
            val t = it.animatedFraction
            for (i in 0 until COUNT) levels[i] = from[i] + (to[i] - from[i]) * t
            voiceLift = voiceFrom + (voiceTo - voiceFrom) * t
            invalidate()
        }
    }

    /** Shows the mix with night mode and dialogue boost as given; [animate] false sets it at once. */
    fun show(night: Boolean, dialogue: Boolean, animate: Boolean = true) {
        animator.cancel()
        from = levels.copyOf()
        voiceFrom = voiceLift
        to = FloatArray(COUNT) { level(it, night) }
        voiceTo = if (dialogue) 1f else 0f
        if (animate && isAttachedToWindow) {
            animator.start()
        } else {
            to.copyInto(levels)
            voiceLift = voiceTo
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = 3 * density
        val barWidth = (width - gap * (COUNT - 1)) / COUNT
        val mid = height / 2f
        for (i in 0 until COUNT) {
            val voice = isVoice(i)
            // A boosted voice stands a little taller as well as brighter.
            val level = (levels[i] + if (voice) VOICE_GAIN * voiceLift else 0f).coerceAtMost(1f)
            val half = level * height / 2f
            val left = i * (barWidth + gap)
            bar.set(left, mid - half, left + barWidth, mid + half)
            paint.color = if (voice) ColorUtils.blendARGB(restColor, voiceColor, voiceLift) else restColor
            canvas.drawRoundRect(bar, 2 * density, 2 * density, paint)
        }
    }

    private companion object {
        const val COUNT = 48
        const val GLIDE_MS = 260L
        const val REST_ALPHA = 0x73
        const val VOICE_GAIN = 0.12f

        fun isVoice(i: Int) = i % 7 < 3

        fun level(i: Int, night: Boolean): Float {
            // The explosion in the middle, a quiet bed either side of it.
            val loud = if (i in 19..25) 0.95f else 0.25f + 0.7f * abs(sin(i * 0.7f) * sin(i * 0.23f + 1f))
            return if (night) 0.35f + (loud - 0.35f) * 0.45f else loud
        }
    }
}
