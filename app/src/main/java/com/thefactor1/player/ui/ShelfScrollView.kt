package com.thefactor1.player.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * A row that scrolls to keep the focused card inside its padding and its row's. The platform's own
 * scroll ignores padding, so moving along a long row pushed the focused card flush against the
 * screen's edge, out of line with everything above it and into the TV's overscan, its ring cut off.
 */
class ShelfScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HorizontalScrollView(context, attrs) {

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val content = getChildAt(0) ?: return 0
        // Where the first card rests, so a card scrolled into view lines up with it (and the row's label).
        val left = scrollX + paddingLeft + content.paddingLeft
        val right = scrollX + width - paddingRight - content.paddingRight
        val delta = when {
            // Off to the right: bring it in, but never so far that its left edge goes out instead.
            rect.right > right && rect.left > left -> minOf(rect.right - right, rect.left - left)
            rect.left < left && rect.right < right -> -minOf(left - rect.left, right - rect.right)
            else -> 0
        }
        val range = (content.width - (width - paddingLeft - paddingRight)).coerceAtLeast(0)
        return delta.coerceIn(-scrollX, range - scrollX)
    }
}
