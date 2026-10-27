package com.thefactor1.player.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubStream
import com.thefactor1.player.ui.FocusGlide
import com.thefactor1.player.ui.Fonts

/**
 * Other versions: the same glass sheet as Refine, one row per copy the hub ranked. Each row is its
 * look and sound in the display face, how it's made and delivered in mono, and the release's own
 * description, dim. Shown at once: skeleton rows while the list is still coming, never a blank.
 */
class VersionsSheet(
    private val root: FrameLayout,
    private val fonts: Fonts,
    /** OK on a row; the view is the row's first line, for the pressed state. */
    private val onPick: (HubStream, TextView) -> Unit,
    private val onClose: () -> Unit,
) {
    private val context = root.context
    private lateinit var glass: ImageView
    private lateinit var title: TextView
    private lateinit var rows: LinearLayout
    private lateinit var glide: FocusGlide
    private var pulse: ValueAnimator? = null

    var isOpen = false
        private set

    /** Opens over [art]; [streams] null means still loading (skeleton rows), empty means none found. */
    fun open(name: String, streams: List<HubStream>?, art: Drawable?) {
        if (root.childCount == 0) build()
        isOpen = true
        title.text = name
        glass.setImageDrawable(art)
        show(streams)
        Mercury.slideIn(root)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        pulse?.cancel()
        Mercury.slideOut(root)
        onClose()
    }

    /** The list arrived (or failed: [message] says why). */
    fun show(streams: List<HubStream>?, message: String? = null) {
        pulse?.cancel()
        rows.removeAllViews()
        glide.clear()
        when {
            streams == null && message == null -> skeleton()
            streams.isNullOrEmpty() -> rows.addView(Mercury.text(context, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.16f).apply {
                text = (message ?: context.getString(R.string.home_versions_none)).uppercase()
                maxLines = 3
            })
            else -> streams.forEach { rows.addView(row(it)) }
        }
        if (isOpen) root.post { (rows.getChildAt(0)?.takeIf { it.isFocusable } ?: root).requestFocus() }
    }

    private fun row(stream: HubStream): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(Mercury.dp(root, 10f), Mercury.dp(root, 9f), Mercury.dp(root, 10f), Mercury.dp(root, 9f))
            // An unconfirmed match stays readable but recedes.
            alpha = if (stream.verified) REST else UNVERIFIED
            tag = stream.verified
            setOnFocusChangeListener { v, has -> if (has) glide.moveTo(v) }
        }
        val marks = listOfNotNull(
            if (stream.best) context.getString(R.string.versions_best) else null,
            if (!stream.verified) context.getString(R.string.versions_unverified) else null,
        )
        if (marks.isNotEmpty()) {
            row.addView(Mercury.text(context, fonts.mercuryMono, 9f, if (stream.best) R.color.mercury_blue else R.color.mercury_muted, 0.22f)
                .apply { text = marks.joinToString(" · ") }, wrap(bottom = 3f))
        }
        val quality = Mercury.title(context, fonts, 18f, mercuryTracking = -0.02f).apply { text = stream.quality() }
        row.addView(quality)
        stream.delivery().takeIf { it.isNotEmpty() }?.let {
            row.addView(Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.12f).apply { text = it.uppercase() }, wrap(top = 4f))
        }
        stream.description?.let {
            row.addView(Mercury.text(context, fonts.ui, 12f, R.color.mercury_muted).apply {
                text = it.lineSequence().first()
                alpha = DESCRIPTION_ALPHA
            }, wrap(top = 3f))
        }
        row.setOnClickListener { onPick(stream, quality) }
        return row
    }

    /** Three placeholder rows, breathing slowly, the shape of what's coming. */
    private fun skeleton() {
        repeat(3) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Mercury.dp(root, 10f), Mercury.dp(root, 12f), Mercury.dp(root, 10f), Mercury.dp(root, 12f))
            }
            row.addView(bar(), LinearLayout.LayoutParams(Mercury.dp(root, 200f), Mercury.dp(root, 14f)))
            row.addView(bar(), LinearLayout.LayoutParams(Mercury.dp(root, 150f), Mercury.dp(root, 8f)).apply { topMargin = Mercury.dp(root, 8f) })
            rows.addView(row)
        }
        pulse = ObjectAnimator.ofFloat(rows, View.ALPHA, 1f, SKELETON_LOW).apply {
            duration = SKELETON_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    rows.alpha = 1f
                }
            })
            start()
        }
    }

    private fun bar() = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = Mercury.dp(root, 3f).toFloat()
            setColor(context.getColor(R.color.mercury_faint))
        }
    }

    private fun build() {
        glass = Mercury.glass(root)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Mercury.dp(root, 31f), Mercury.dp(root, Mercury.SAFE_Y), Mercury.dp(root, Mercury.SAFE_X), 0)
        }
        root.addView(column)
        column.addView(Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.22f).apply { setText(R.string.home_versions_kicker) })
        title = Mercury.title(context, fonts, 26f)
        column.addView(title, wrap(top = 6f, bottom = 12f))
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, Mercury.dp(root, Mercury.SAFE_Y + 12f))
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val frame = FrameLayout(context)
        scroll.addView(frame)
        val hover = View(context).apply { setBackgroundResource(R.drawable.mercury_hover); visibility = View.INVISIBLE }
        frame.addView(hover, FrameLayout.LayoutParams(0, 0))
        glide = FocusGlide(hover) { view, amount ->
            val rest = if (view.tag == false) UNVERIFIED else REST
            view.alpha = rest + (1f - rest) * amount
        }
        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(rows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun wrap(top: Float = 0f, bottom: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Mercury.dp(root, top)
            bottomMargin = Mercury.dp(root, bottom)
        }

    private companion object {
        const val REST = 0.75f
        const val UNVERIFIED = 0.45f
        const val DESCRIPTION_ALPHA = 0.7f
        const val SKELETON_LOW = 0.45f
        const val SKELETON_MS = 900L
    }
}
