package com.thefactor1.player.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A scroll view as tall as its content, up to [maxHeight]; past that it scrolls. */
class MaxHeightScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    var maxHeight = Int.MAX_VALUE
        set(value) {
            if (field != value) { field = value; requestLayout() }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, if (maxHeight == Int.MAX_VALUE) heightMeasureSpec else limit)
    }
}
