package com.thefactor1.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.thefactor1.player.R

/**
 * The on/off mark on a toggle row: an outlined pill when off, a filled one with the knob to the
 * right when on. It takes the row's focus state, so it flips to dark on the white focused row.
 */
class SwitchMark @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var on = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** How focused its row is, 0 to 1, for rows whose highlight glides; below 0 the focus state decides. */
    var focusAmount = -1f
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    private val density = resources.displayMetrics.density
    private val stroke = 1.5f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val light = context.getColor(R.color.ink)
    private val lightFaint = context.getColor(R.color.ink_faint)
    private val dark = context.getColor(R.color.on_focus)
    private val darkSoft = context.getColor(R.color.on_focus_secondary)

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = if (focusAmount >= 0f) focusAmount else if (drawableState.contains(android.R.attr.state_focused)) 1f else 0f
        val h = height.toFloat()
        val radius = h / 2f
        track.set(stroke / 2f, stroke / 2f, width - stroke / 2f, h - stroke / 2f)
        val knobRadius = radius - 4f * density
        if (on) {
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.blendARGB(light, dark, f)
            canvas.drawRoundRect(track, radius, radius, paint)
            paint.color = ColorUtils.blendARGB(dark, light, f)
            canvas.drawCircle(width - radius, radius, knobRadius, paint)
        } else {
            val color = ColorUtils.blendARGB(lightFaint, darkSoft, f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = color
            canvas.drawRoundRect(track, radius, radius, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(radius, radius, knobRadius, paint)
        }
    }
}
