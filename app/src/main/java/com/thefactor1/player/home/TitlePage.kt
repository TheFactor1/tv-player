package com.thefactor1.player.home

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import com.thefactor1.player.R
import com.thefactor1.player.hub.Badges
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion

/**
 * The title page (Details): over the title's backdrop, its logo or name, quality badges, the
 * overview, Play, Other versions, and Keep this or "In your library".
 */
class TitlePage(
    private val root: FrameLayout,
    private val fonts: Fonts,
    private val play: (HubItem) -> Unit,
    private val versions: (HubItem) -> Unit,
    private val keep: (HubItem) -> Unit,
) {
    private val context = root.context
    private lateinit var logo: ImageView
    private lateinit var name: TextView
    private lateinit var chips: LinearLayout
    private lateinit var credits: TextView
    private lateinit var overview: TextView
    private lateinit var playButton: TextView
    private lateinit var keepButton: TextView
    private lateinit var owned: TextView

    var item: HubItem? = null
        private set

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    fun open(item: HubItem) {
        if (root.childCount == 0) build()
        logo.visibility = View.GONE
        name.visibility = View.VISIBLE
        show(item)
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.translationY = Mercury.dp(root, Motion.RISE_DP).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        playButton.requestFocus()
    }

    fun close() {
        item = null
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit)
            .withEndAction { root.visibility = View.GONE }.start()
    }

    /** Fresh details for the title on screen (full metadata arrived, or it was kept). */
    fun show(item: HubItem) {
        this.item = item
        name.text = item.title
        chips.removeAllViews()
        item.badges.all().forEach { badge ->
            chips.addView(Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_fg, 0.16f).apply {
                text = badge.uppercase()
                setBackgroundResource(R.drawable.mercury_chip)
                setPadding(Mercury.dp(root, 7f), Mercury.dp(root, 3f), Mercury.dp(root, 7f), Mercury.dp(root, 3f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Mercury.dp(root, 5f) })
        }
        chips.visibility = if (chips.childCount == 0) View.GONE else View.VISIBLE
        val mbps = item.badges.mbps?.let { "${it.roundToInt()} MBPS" }
        credits.text = listOfNotNull(Mercury.credits(item.copy(badges = Badges(null, null, null)), showLibrary = false).ifEmpty { null }, mbps)
            .joinToString(" · ")
        overview.text = item.overview
        overview.visibility = if (item.overview.isNullOrEmpty()) View.GONE else View.VISIBLE
        val episode = HomePlay.episodeFor(item)
        playButton.text = if (item.isSeries && episode != null) context.getString(R.string.home_play_episode, episode.season, episode.episode)
        else context.getString(R.string.home_play)
        val keepHadFocus = keepButton.isFocused
        keepButton.visibility = if (item.inLibrary) View.GONE else View.VISIBLE
        owned.visibility = if (item.inLibrary) View.VISIBLE else View.GONE
        if (keepHadFocus && item.inLibrary) playButton.requestFocus()
    }

    fun showLogo(bitmap: Bitmap) {
        logo.setImageBitmap(bitmap)
        logo.alpha = 0f
        logo.visibility = View.VISIBLE
        logo.animate().alpha(1f).setDuration(Motion.ENTER_MS).start()
        name.visibility = View.GONE
    }

    private fun build() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(Mercury.dp(root, 34f), 0, 0, Mercury.dp(root, 48f))
        }
        root.addView(column, FrameLayout.LayoutParams((root.resources.displayMetrics.widthPixels * WIDTH).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        logo = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            maxWidth = Mercury.dp(root, 400f)
            maxHeight = Mercury.dp(root, 110f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        column.addView(logo, wrap(bottom = 14f))
        name = Mercury.text(context, fonts.display, 50f, tracking = -0.03f).apply { maxLines = 2 }
        column.addView(name, wrap(bottom = 12f))
        chips = LinearLayout(context)
        column.addView(chips, wrap(bottom = 10f))
        credits = Mercury.text(context, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.14f)
        column.addView(credits, wrap(bottom = 10f))
        overview = Mercury.text(context, fonts.ui, 14f).apply {
            maxLines = 5
            alpha = OVERVIEW_ALPHA
            setLineSpacing(0f, 1.4f)
        }
        column.addView(overview, wrap(bottom = 18f))

        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        column.addView(buttons)
        playButton = button { item?.let(play) }
        buttons.addView(playButton, wrap(end = 10f))
        buttons.addView(button(R.string.home_versions) { item?.let(versions) }, wrap(end = 10f))
        keepButton = button(R.string.home_keep) { item?.let(keep) }
        buttons.addView(keepButton, wrap(end = 14f))
        owned = Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.14f).apply { setText(R.string.home_in_library) }
        buttons.addView(owned)
    }

    private fun button(label: Int = R.string.home_play, click: () -> Unit) =
        TextView(context, null, 0, R.style.ControlButton).apply {
            setText(label)
            typeface = fonts.ui
            textSize = 15f
            height = Mercury.dp(root, 42f)
            setPadding(Mercury.dp(root, 20f), 0, Mercury.dp(root, 20f), 0)
            setOnClickListener { click() }
        }

    private fun wrap(bottom: Float = 0f, end: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Mercury.dp(root, bottom)
            marginEnd = Mercury.dp(root, end)
        }

    private companion object {
        const val WIDTH = 0.5f
        const val OVERVIEW_ALPHA = 0.8f
    }
}
