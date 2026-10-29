package com.thefactor1.player.home

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.thefactor1.player.R
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion
import java.util.Date

/**
 * Home's header, one band on one baseline: the wordmark, a hairline, Search, the tabs, FILTER, and on
 * the right ATTENTION (only when something needs it), Settings and the clock. One meniscus sits
 * under the active tab and glides to whatever has focus while focus is up here.
 */
class Header(
    private val root: FrameLayout,
    private val fonts: Fonts,
    labels: List<Int>,
    private val onTab: (Int) -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onSettings: () -> Unit,
) {
    private val context = root.context
    private val main = Handler(Looper.getMainLooper())
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val meniscus = View(context)
    val search: ImageView
    val tabs: List<TextView>
    val filter: TextView
    private val attention: LinearLayout
    private val settings: TextView
    private val clock: TextView
    private val tick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(context).format(Date())
            main.postDelayed(this, CLOCK_MS)
        }
    }

    var active = 0
        private set
    private var placed = false
    var searchOpen = false
        set(value) {
            field = value
            paint()
        }

    init {
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Mercury.dp(root, HEIGHT_DP)))
        row.addView(if (Mercury.paper) {
            Mercury.title(context, fonts, 16f, mercuryTracking = 0f).apply { setText(R.string.home_masthead) }
        } else {
            Mercury.title(context, fonts, 12f, mercuryTracking = 0.08f).apply {
                setText(R.string.home_wordmark)
                alpha = WORDMARK_ALPHA
            }
        })
        row.addView(View(context).apply { setBackgroundColor(Mercury.color(context, R.color.mercury_faint)) },
            LinearLayout.LayoutParams(Mercury.dp(root, 1f), Mercury.dp(root, 20f)).apply {
                marginStart = Mercury.dp(root, 14f)
                marginEnd = Mercury.dp(root, 6f)
            })
        search = ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Mercury.color(context, R.color.mercury_fg))
            contentDescription = context.getString(R.string.home_search)
            val pad = Mercury.dp(root, 6f)
            setPadding(pad, pad, pad, pad)
        }
        row.addView(control(search) { onSearch() }, wrap(end = 10f))
        tabs = labels.mapIndexed { index, label ->
            Mercury.text(context, if (Mercury.paper) fonts.mercuryMono else fonts.ui, if (Mercury.paper) 11f else 14f,
                tracking = if (Mercury.paper) 0.05f else 0f).apply {
                setText(label)
                isAllCaps = Mercury.paper
                setPadding(0, Mercury.dp(root, 6f), 0, Mercury.dp(root, 6f))
            }.also { row.addView(control(it) { onTab(index) }, wrap(end = if (Mercury.paper) PAPER_TAB_GAP_DP else TAB_GAP_DP)) }
        }
        filter = Mercury.text(context, fonts.mercuryMono, 11f, tracking = 0.18f).apply {
            setPadding(0, Mercury.dp(root, 6f), 0, Mercury.dp(root, 6f))
        }
        row.addView(control(filter) { onFilter() }, wrap(start = 4f, end = 16f))
        row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        attention = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(context.getColor(R.color.mercury_amber))
                }
            }, LinearLayout.LayoutParams(Mercury.dp(root, 6f), Mercury.dp(root, 6f)).apply { marginEnd = Mercury.dp(root, 8f) })
            addView(Mercury.text(context, fonts.mercuryMono, 12f, R.color.mercury_amber, 0.2f).apply { setText(R.string.home_attention) })
        }
        row.addView(attention, wrap(end = 22f))
        settings = Mercury.text(context, fonts.mercuryMono, 11f, tracking = 0.18f).apply {
            setText(R.string.home_settings_short)
            setPadding(0, Mercury.dp(root, 6f), 0, Mercury.dp(root, 6f))
        }
        row.addView(control(settings) { onSettings() }, wrap(end = 22f))
        clock = Mercury.text(context, fonts.mercuryMono, 12f, tracking = 0.12f).apply { alpha = WORDMARK_ALPHA }
        row.addView(clock)

        if (Mercury.paper) {
            // The masthead's double rule runs the full width under the band; the red meniscus rides on it.
            root.addView(View(context).apply { setBackgroundResource(R.drawable.paper_double_rule) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Mercury.dp(root, 4f), Gravity.BOTTOM))
        }
        meniscus.background = Mercury.meniscus(context)
        meniscus.pivotX = 0f
        meniscus.visibility = View.INVISIBLE
        root.addView(meniscus, FrameLayout.LayoutParams(Mercury.dp(root, MENISCUS_DP), Mercury.dp(root, 2f), Gravity.BOTTOM))
        // Placed without motion once the tabs have widths; after that it only ever glides.
        row.viewTreeObserver.addOnGlobalLayoutListener { if (!placed && tabs[active].width > 0) glide(animate = false).also { placed = true } }
        tick.run()
    }

    fun setActive(index: Int) {
        active = index
        paint()
    }

    /** FILTER shows only where filtering makes sense, and says how many choices are on. */
    fun setFilter(count: Int, shown: Boolean) {
        filter.text = if (count > 0) context.getString(R.string.home_filter_on, count) else context.getString(R.string.home_filter)
        filter.visibility = if (shown) View.VISIBLE else View.GONE
        // The tabs after it may shift: settle the meniscus once the row has laid out again.
        row.post { glide(animate = false) }
        paint()
    }

    fun setAttention(on: Boolean) {
        attention.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun contains(view: View?): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === row) return true
            current = current.parent as? View
        }
        return false
    }

    fun focusActive() {
        (if (searchOpen) search else tabs[active]).requestFocus()
    }

    fun stop() = main.removeCallbacks(tick)

    /** Tabs at three strengths: 50 % at rest, 85 % focused, full when active. */
    private fun paint() {
        tabs.forEachIndexed { index, tab ->
            tab.alpha = when {
                index == active && !searchOpen -> 1f
                tab.isFocused -> FOCUSED
                else -> REST
            }
        }
        search.alpha = if (searchOpen) 1f else if (search.isFocused) FOCUSED else REST
        filter.alpha = if (filter.isFocused) FOCUSED else REST
        settings.alpha = if (settings.isFocused) FOCUSED else REST
        glide(animate = true)
    }

    /** The meniscus moves to the focused control while focus is in the header, else to the active tab; transform only. */
    private fun glide(animate: Boolean) {
        val focused = listOf(search, filter, settings, *tabs.toTypedArray()).firstOrNull { it.isFocused }
        val target = focused ?: if (searchOpen) search else tabs[active]
        if (target.width == 0) return
        val x = (row.left + target.left).toFloat()
        val scale = target.width / Mercury.dp(root, MENISCUS_DP).toFloat()
        meniscus.visibility = View.VISIBLE
        meniscus.animate().cancel()
        if (!animate) {
            meniscus.translationX = x
            meniscus.scaleX = scale
            return
        }
        meniscus.animate().translationX(x).scaleX(scale).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.enter).start()
    }

    private fun control(view: View, click: () -> Unit): View = view.apply {
        isFocusable = true
        isClickable = true
        setOnClickListener { click() }
        setOnFocusChangeListener { _, _ -> paint() }
    }

    private fun wrap(start: Float = 0f, end: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Mercury.dp(root, start)
            marginEnd = Mercury.dp(root, end)
        }

    private companion object {
        const val HEIGHT_DP = 34f
        const val TAB_GAP_DP = 20f
        /** Broadsheet's caps tabs share the band with a wide masthead. */
        const val PAPER_TAB_GAP_DP = 12f
        const val MENISCUS_DP = 100f
        const val REST = 0.5f
        const val FOCUSED = 0.85f
        const val WORDMARK_ALPHA = 0.85f
        const val CLOCK_MS = 20_000L
    }
}
