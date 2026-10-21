package com.thefactor1.player.ui

import android.view.View
import android.widget.ScrollView

/**
 * Opens a list with [row] (the current choice) at the top of its scroller, just below the fading
 * edge, so a long list never opens with the selection out of sight. Runs after layout, when the
 * row has a position; a list that already fits doesn't move.
 */
fun scrollRowToTop(row: View) {
    val scroller = (row.parent as? View)?.parent as? ScrollView ?: return
    row.post {
        val list = row.parent as? View ?: return@post
        scroller.scrollTo(0, (list.top + row.top - scroller.verticalFadingEdgeLength).coerceAtLeast(0))
    }
}
