package com.thefactor1.player.home

import android.animation.AnimatorInflater
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.ui.FocusGlide
import com.thefactor1.player.ui.Fonts

/**
 * The Refine sheet: one glass layer over the right of the screen, text first. Genre, Length, Format
 * and Sort apply live through [onChange]; the list behind updates as they do. Back or Done closes it.
 */
class RefineSheet(
    private val root: FrameLayout,
    private val fonts: Fonts,
    private val onChange: (Refine) -> Unit,
    private val onClose: () -> Unit,
) {
    private val context = root.context
    private var refine = Refine()
    private var items: List<HubItem> = emptyList()
    /** Each control's way of redrawing itself after a change. */
    private val redraws = mutableListOf<() -> Unit>()
    private lateinit var count: TextView
    private lateinit var clear: TextView
    private lateinit var done: TextView
    private lateinit var glass: ImageView
    private lateinit var glide: FocusGlide
    /** Where focus lands when the sheet opens. */
    private var first: View? = null

    var isOpen = false
        private set

    /** Opens over [art] (the backdrop on screen, blurred behind the sheet) for [items], the collection as loaded. */
    fun open(refine: Refine, items: List<HubItem>, art: Drawable?) {
        this.refine = refine
        this.items = items
        isOpen = true
        build()
        glass.setImageDrawable(art)
        Mercury.slideIn(root)
        root.post { (first?.takeIf { it.isFocusable } ?: done).requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        Mercury.slideOut(root)
        onClose()
    }

    /** The collection behind changed (a page loaded, a tab switched): counts follow it. */
    fun update(items: List<HubItem>) {
        this.items = items
        if (isOpen) redraw()
    }

    private fun set(next: Refine) {
        refine = next
        onChange(next)
        redraw()
    }

    private fun redraw() {
        val shown = refine.apply(items).size
        count.text = context.resources.getQuantityString(R.plurals.refine_count, shown, shown)
        clear.text = if (refine.count > 0) context.getString(R.string.refine_clear_count, refine.count) else context.getString(R.string.refine_clear)
        clear.alpha = if (refine.count > 0 || clear.isFocused) 1f else Mercury.CONTROL_REST
        redraws.forEach { it() }
    }

    private fun build() {
        root.removeAllViews()
        redraws.clear()
        first = null
        glass = Mercury.glass(root)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Mercury.dp(root, 31f), Mercury.dp(root, Mercury.SAFE_Y), Mercury.dp(root, Mercury.SAFE_X), 0)
        }
        root.addView(column)
        column.addView(Mercury.title(context, fonts, 28f).apply { setText(R.string.refine_title) })
        count = Mercury.text(context, fonts.mercuryMono, 12f, R.color.mercury_muted, 0.2f)
        column.addView(count, margins(top = 6f))
        column.addView(hairline(), margins(top = 10f, height = 1f))

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val frame = FrameLayout(context)
        scroll.addView(frame)
        val hover = View(context).apply { setBackgroundResource(R.drawable.mercury_hover); visibility = View.INVISIBLE }
        frame.addView(hover, FrameLayout.LayoutParams(0, 0))
        glide = FocusGlide(hover) { view, amount -> paint(view, amount) }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(body)

        section(body, R.string.refine_genre)
        val genres = (Refine.genresOf(items) + refine.genres).distinct().sorted()
        genres.chunked(2).forEach { pair ->
            val line = LinearLayout(context)
            pair.forEach { genre -> line.addView(genreRow(genre), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            if (pair.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            body.addView(line)
        }
        body.addView(hairline(), margins(top = 8f, height = 1f))

        section(body, R.string.refine_length)
        body.addView(segments(Refine.LENGTHS.map { it.label }, { it in refine.lengths }) { set(refine.toggleLength(it)) })
        body.addView(hairline(), margins(top = 8f, height = 1f))

        section(body, R.string.refine_format)
        body.addView(segments(Refine.FORMATS.map { it.label }, { it in refine.formats }) { set(refine.toggleFormat(it)) })
        body.addView(hairline(), margins(top = 8f, height = 1f))

        section(body, R.string.refine_sort)
        Refine.SORTS.forEachIndexed { index, sort -> body.addView(sortRow(sort.label, index)) }

        val footer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Mercury.dp(root, 10f), 0, Mercury.dp(root, Mercury.SAFE_Y))
        }
        column.addView(footer)
        clear = Mercury.text(context, fonts.mercuryMono, 10f, tracking = 0.22f).apply {
            focusable(this)
            setOnClickListener { set(Refine()) }
            setOnFocusChangeListener { v, has ->
                glide.release()
                (v as TextView).paintFlags = if (has) v.paintFlags or Paint.UNDERLINE_TEXT_FLAG else v.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                redraw()
            }
        }
        footer.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        done = Mercury.text(context, fonts.uiBold, 14f, R.color.ground).apply {
            setText(R.string.refine_done)
            setBackgroundResource(R.drawable.mercury_done)
            setPadding(Mercury.dp(root, 26f), Mercury.dp(root, 9f), Mercury.dp(root, 26f), Mercury.dp(root, 9f))
            stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.control_button_state)
            focusable(this)
            setOnClickListener { close() }
            setOnFocusChangeListener { _, has -> if (has) glide.release() }
        }
        footer.addView(done)
        redraw()
    }

    private fun genreRow(genre: String): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Mercury.dp(root, 4f), Mercury.dp(root, 8f), Mercury.dp(root, 4f))
        }
        val bar = View(context).apply { setBackgroundColor(Mercury.color(context, R.color.mercury_blue)) }
        row.addView(bar, LinearLayout.LayoutParams(Mercury.dp(root, 3f), Mercury.dp(root, 13f)).apply { marginEnd = Mercury.dp(root, 7f) })
        row.addView(Mercury.text(context, fonts.ui, 14f).apply { text = genre }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val number = Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted)
        row.addView(number)
        val on = { genre in refine.genres }
        control(row, on) { set(refine.toggleGenre(genre)) }
        if (first == null) first = row
        redraws += {
            val n = refine.countFor(genre, items)
            number.text = n.toString()
            bar.visibility = if (on()) View.VISIBLE else View.INVISIBLE
            // A genre nothing here has can't be picked; it stays as a quiet reminder of what exists.
            row.isFocusable = n > 0 || on()
            row.alpha = if (!row.isFocusable) SKIP_ALPHA else if (on() || row.isFocused) 1f else Mercury.CONTROL_REST
        }
        return row
    }

    /** A row of equal text choices, each with the meniscus under it while on. */
    private fun segments(labels: List<String>, on: (Int) -> Boolean, toggle: (Int) -> Unit): View {
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        labels.forEachIndexed { index, label ->
            if (index > 0) line.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.mercury_faint)) },
                LinearLayout.LayoutParams(Mercury.dp(root, 1f), Mercury.dp(root, 16f)))
            val segment = Mercury.text(context, fonts.ui, 14f).apply {
                text = label
                gravity = Gravity.CENTER
                setPadding(0, Mercury.dp(root, 5f), 0, Mercury.dp(root, 5f))
            }
            control(segment, { on(index) }) { toggle(index) }
            redraws += { segment.background = if (on(index)) Mercury.meniscus(context) else null }
            line.addView(segment, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return line
    }

    private fun sortRow(label: String, index: Int): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Mercury.dp(root, 6f), Mercury.dp(root, 4f), Mercury.dp(root, 6f), Mercury.dp(root, 4f))
        }
        val dot = View(context)
        row.addView(dot, LinearLayout.LayoutParams(Mercury.dp(root, 8f), Mercury.dp(root, 8f)).apply { marginEnd = Mercury.dp(root, 10f) })
        row.addView(Mercury.text(context, fonts.ui, 14f).apply { text = label })
        val on = { refine.sort == index }
        control(row, on) { set(refine.pickSort(index)) }
        redraws += {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (on()) setColor(Mercury.color(context, R.color.mercury_blue)) else setStroke(Mercury.dp(root, 1f), context.getColor(R.color.mercury_muted))
            }
        }
        return row
    }

    private fun control(view: View, on: () -> Boolean, click: () -> Unit) {
        focusable(view)
        view.setTag(R.id.item_value, on)
        view.setOnClickListener { click() }
        view.setOnFocusChangeListener { v, has -> if (has) glide.moveTo(v) }
        redraws += { if (!view.isFocused) view.alpha = if (on()) 1f else Mercury.CONTROL_REST }
    }

    @Suppress("UNCHECKED_CAST")
    private fun paint(view: View, amount: Float) {
        val on = (view.getTag(R.id.item_value) as? () -> Boolean)?.invoke() == true
        view.alpha = if (on) 1f else Mercury.CONTROL_REST + (1f - Mercury.CONTROL_REST) * amount
    }

    private fun focusable(view: View) {
        view.isFocusable = true
        view.isClickable = true
    }

    private fun section(body: LinearLayout, label: Int) {
        body.addView(Mercury.text(context, fonts.mercuryMono, 9f, R.color.mercury_muted, 0.28f).apply { setText(label) }, margins(top = 10f, bottom = 6f))
    }

    private fun hairline() = View(context).apply { setBackgroundColor(context.getColor(R.color.mercury_faint)) }

    /** Full-width layout params; [height] in dp, or wrap the content when 0. */
    private fun margins(top: Float = 0f, bottom: Float = 0f, height: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height > 0) Mercury.dp(root, height) else ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Mercury.dp(root, top)
            bottomMargin = Mercury.dp(root, bottom)
        }

    private companion object {
        const val SKIP_ALPHA = 0.35f
    }
}
