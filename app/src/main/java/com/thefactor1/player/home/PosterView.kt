package com.thefactor1.player.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem

/**
 * A title's picture with how far into it you are along the bottom: the one way every screen draws
 * a poster (or a backdrop card), so the progress bar can't look different from one place to the next.
 */
class PosterView(context: Context, private val backdrop: Boolean = false) : FrameLayout(context) {
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val track = FrameLayout(context).apply { setBackgroundResource(R.drawable.home_progress_track) }
    private val fill = View(context).apply { setBackgroundResource(R.drawable.home_progress_fill) }

    init {
        setBackgroundResource(R.drawable.home_card_bg)
        clipToOutline = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        track.addView(fill, LayoutParams(0, LayoutParams.MATCH_PARENT))
        val inset = Mercury.dp(this, 5f)
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, Mercury.dp(this, 3f), Gravity.BOTTOM).apply { setMargins(inset, 0, inset, inset) })
    }

    fun bind(item: HubItem, widthPx: Int) =
        bind(if (backdrop) HomeArt.backdrop(item) else HomeArt.poster(item), Mercury.progress(item), widthPx)

    /** [url]'s picture with [fraction] (0–1) watched along the bottom, or no bar when null. */
    fun bind(url: String?, fraction: Float?, widthPx: Int) {
        track.visibility = if (fraction == null) View.GONE else View.VISIBLE
        if (fraction != null) track.post { fill.layoutParams = fill.layoutParams.apply { width = (track.width * fraction).toInt() } }
        HomeArt.into(image, url, widthPx)
    }
}
