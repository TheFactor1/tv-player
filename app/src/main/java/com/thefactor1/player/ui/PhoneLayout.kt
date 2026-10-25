package com.thefactor1.player.ui

import android.app.Activity
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.thefactor1.player.R

/**
 * The player's screen resized for a phone held sideways: the TV's 64 dp margins and 10-foot type
 * come down to phone sizes, buttons and rows grow to finger size, and everything near an edge
 * keeps clear of the camera cutout, rounded corners and the gesture bar. The layout file stays the
 * TV's; this only runs in touch mode.
 */
class PhoneLayout(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    private val root: View = activity.findViewById(R.id.root)

    /** The room kept on the left and right of the screen, in px, for anything that sits at a side. */
    var left = dp(GUTTER_DP)
        private set
    var right = dp(GUTTER_DP)
        private set
    private var bottom = dp(BOTTOM_DP)
    private var top = dp(TOP_DP)

    /** Called again whenever the room changes, e.g. the phone turned round to put its notch on the other side. */
    var onChanged: (() -> Unit)? = null

    init {
        // The picture runs under the cutout; only the controls keep clear of it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        resizeOnce()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // The bars are hidden but come back with a swipe, so their room is kept either way.
            val safe = Insets.max(
                insets.getInsets(WindowInsetsCompat.Type.displayCutout()),
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()),
            )
            left = dp(GUTTER_DP) + safe.left
            right = dp(GUTTER_DP) + safe.right
            top = dp(TOP_DP) + safe.top
            bottom = dp(BOTTOM_DP) + safe.bottom
            placeEdges()
            onChanged?.invoke()
            insets
        }
        placeEdges()
        ViewCompat.requestApplyInsets(root)
    }

    /** Sizes that don't depend on the screen's edges. */
    private fun resizeOnce() {
        text(R.id.title, 20f)
        text(R.id.source, 13f)
        text(R.id.clock, 16f)
        text(R.id.ends_at, 12f)
        text(R.id.elapsed, 13f)
        text(R.id.remaining, 13f)
        text(R.id.center_toast, 16f)
        text(R.id.skip_prompt, 15f)
        text(R.id.exit_hint, 13f)
        text(R.id.stats_overlay, 11f)
        text(R.id.meta_clock, 16f)
        text(R.id.loading_title, 30f)
        text(R.id.meta_episode, 15f)
        text(R.id.meta_description, 14f)
        text(R.id.loading_line, 14f)
        text(R.id.booth_title, 30f)
        text(R.id.booth_episode, 16f)

        // A finger needs more than the remote's thin line to land on.
        size(R.id.seek_bar, height = dp(SEEK_HEIGHT_DP))
        size(R.id.skip_prompt, height = dp(44f))
        for (id in BUTTONS) {
            val button = activity.findViewById<ImageView>(id)
            size(id, width = dp(BUTTON_DP), height = dp(BUTTON_DP))
            val pad = dp(BUTTON_PAD_DP)
            button.setPadding(pad, pad, pad, pad)
            margins(id, end = if (id == R.id.btn_more) 0 else dp(BUTTON_GAP_DP))
        }
        size(R.id.button_glide, width = dp(BUTTON_DP), height = dp(BUTTON_DP))
        margins(R.id.button_frame, top = dp(6f))
        // No focus to follow on a touchscreen, so no label under the buttons and no room kept for one.
        size(R.id.label_row, height = 0)
        margins(R.id.label_row, top = 0)
        size(R.id.side_panel, width = dp(PANEL_WIDTH_DP))

        activity.findViewById<ImageView>(R.id.meta_logo).apply {
            maxWidth = dp(260f)
            maxHeight = dp(72f)
        }
        activity.findViewById<ImageView>(R.id.booth_logo).apply {
            maxWidth = dp(280f)
            maxHeight = dp(72f)
        }
        activity.findViewById<TextView>(R.id.loading_title).maxWidth = dp(480f)
        activity.findViewById<TextView>(R.id.meta_description).maxLines = 2
        for (id in listOf(R.id.booth_log, R.id.booth_status)) size(id, width = dp(420f))
        margins(R.id.booth_log, top = dp(18f))
    }

    /** Everything that sits against an edge, kept [left], [right], [top] and [bottom] clear. */
    private fun placeEdges() {
        activity.findViewById<View>(R.id.info_bar).setPadding(left, top, right, dp(48f))
        activity.findViewById<View>(R.id.control_bar).setPadding(left, dp(56f), right, bottom)
        // The seek bar's ends reach out by the thumb's halo, as on the TV.
        activity.findViewById<View>(R.id.mini_bar).setPadding(left - dp(13f), dp(40f), right, bottom)
        margins(R.id.skip_back, start = left)
        margins(R.id.skip_forward, end = right)
        margins(R.id.skip_prompt, end = right, bottom = bottom + dp(36f))
        margins(R.id.exit_hint, end = right, bottom = bottom + dp(4f))
        margins(R.id.stats_overlay, start = left, top = top)
        margins(R.id.touch_level, top = top)
        margins(R.id.meta_clock, top = top, end = right)
        margins(R.id.meta_block, start = left, end = right, bottom = bottom + dp(20f))
        margins(R.id.loading_source, end = right, bottom = bottom)
        margins(R.id.booth_cue, top = top + dp(4f), end = right)
        activity.findViewById<View?>(R.id.resume_chip)?.let { margins(it, end = right, bottom = bottom + dp(20f)) }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun text(id: Int, sp: Float) = activity.findViewById<TextView>(id).setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)

    private fun size(id: Int, width: Int? = null, height: Int? = null) {
        val view = activity.findViewById<View>(id)
        val params = view.layoutParams
        width?.let { params.width = it }
        height?.let { params.height = it }
        view.layoutParams = params
    }

    private fun margins(id: Int, start: Int? = null, top: Int? = null, end: Int? = null, bottom: Int? = null) =
        margins(activity.findViewById(id), start, top, end, bottom)

    private fun margins(view: View, start: Int? = null, top: Int? = null, end: Int? = null, bottom: Int? = null) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        start?.let { params.marginStart = it }
        top?.let { params.topMargin = it }
        end?.let { params.marginEnd = it }
        bottom?.let { params.bottomMargin = it }
        view.layoutParams = params
    }

    private companion object {
        val BUTTONS = listOf(R.id.btn_play, R.id.btn_audio, R.id.btn_subtitles, R.id.btn_picture, R.id.btn_more)
        const val GUTTER_DP = 20f
        const val TOP_DP = 14f
        const val BOTTOM_DP = 10f
        const val SEEK_HEIGHT_DP = 40f
        const val BUTTON_DP = 48f
        const val BUTTON_PAD_DP = 13f
        const val BUTTON_GAP_DP = 8f
        const val PANEL_WIDTH_DP = 290f
    }
}
