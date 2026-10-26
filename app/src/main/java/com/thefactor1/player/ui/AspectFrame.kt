package com.thefactor1.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** A frame as wide as its parent gives it and [ratio] times narrower in height: 16:9 unless set. */
class AspectFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var ratio = 16f / 9f
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec((width / ratio).toInt(), View.MeasureSpec.EXACTLY))
    }
}
