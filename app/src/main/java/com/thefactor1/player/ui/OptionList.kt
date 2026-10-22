package com.thefactor1.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.thefactor1.player.R

/** One row of a settings list: a choice, a toggle, or a setting that opens its own list of choices. */
class Option(
    val label: String,
    val detail: String? = null,
    val checked: Boolean = false,
    /** A setting with several choices opens them as a list instead of cycling on each click. */
    val opens: OptionPage? = null,
    val action: () -> Unit = {},
)

/** A list of choices one level down; picking one applies it and steps back up. */
class OptionPage(val title: String, val options: () -> List<Option>)

/**
 * Renders settings rows into [container] with one level of pages, for the home screen. Rows are
 * rebuilt only when the page changes; a toggle refreshes its own row so focus never jumps.
 */
class OptionList(
    private val context: Context,
    private val container: LinearLayout,
    private val fonts: Fonts,
    private val onPageChanged: (OptionPage?) -> Unit,
    private val root: () -> List<RowSpec>,
) {
    /** A root row: a section heading, or an option (rebuilt from scratch on refresh). */
    sealed class RowSpec {
        class Heading(val text: String) : RowSpec()
        class Item(val option: () -> Option) : RowSpec()
    }

    private val density = context.resources.displayMetrics.density
    private var page: OptionPage? = null
    private var pageOpenerIndex = -1

    val inPage: Boolean get() = page != null

    fun show(focusIndex: Int = -1) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val current = page
        val focusables = mutableListOf<View>()
        var target: View? = null

        if (current == null) {
            root().forEachIndexed { index, spec ->
                when (spec) {
                    is RowSpec.Heading -> container.addView(heading(spec.text, first = index == 0))
                    is RowSpec.Item -> {
                        val row = inflater.inflate(R.layout.item_panel, container, false)
                        bind(row, spec.option())
                        row.setOnClickListener {
                            val option = spec.option()
                            val opens = option.opens
                            if (opens != null) {
                                openPage(opens, index)
                            } else {
                                option.action()
                                bind(row, spec.option())
                            }
                        }
                        addRow(row)
                        focusables += row
                        if (index == focusIndex) target = row
                    }
                }
            }
        } else {
            current.options().forEach { option ->
                val row = inflater.inflate(R.layout.item_panel, container, false)
                bind(row, option)
                row.setOnClickListener {
                    option.action()
                    closePage()
                }
                addRow(row)
                focusables += row
                if (target == null && option.checked) target = row
            }
        }

        focusables.firstOrNull()?.let { it.nextFocusUpId = it.id }
        focusables.lastOrNull()?.let { it.nextFocusDownId = it.id }
        focusables.forEach { it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        (target ?: focusables.firstOrNull())?.let {
            it.requestFocus()
            it.jumpDrawablesToCurrentState()
            scrollRowToTop(it)
        }
    }

    /** Back: steps out of a page. Returns false at the top level so the caller can leave. */
    fun back(): Boolean {
        if (page == null) return false
        closePage()
        return true
    }

    private fun openPage(next: OptionPage, fromIndex: Int) {
        page = next
        pageOpenerIndex = fromIndex
        onPageChanged(next)
        show()
    }

    private fun closePage() {
        page = null
        onPageChanged(null)
        show(focusIndex = pageOpenerIndex)
    }

    private fun addRow(row: View) {
        row.id = View.generateViewId()
        (row.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (2 * density).toInt()
        container.addView(row)
    }

    private fun heading(text: String, first: Boolean) = TextView(context).apply {
        this.text = text.uppercase()
        typeface = fonts.semibold
        textSize = 13f
        letterSpacing = 0.1f
        setTextColor(context.getColor(R.color.ink_secondary))
        val side = (14 * density).toInt()
        setPadding(side, ((if (first) 4 else 22) * density).toInt(), side, (8 * density).toInt())
    }

    private fun bind(row: View, option: Option) {
        val label = row.findViewById<TextView>(R.id.item_label)
        label.text = option.label
        label.typeface = fonts.semibold
        val detail = row.findViewById<TextView>(R.id.item_detail)
        detail.text = option.detail
        detail.typeface = fonts.medium
        detail.visibility = if (option.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        val check = row.findViewById<ImageView>(R.id.item_check)
        check.setImageResource(if (option.opens != null) R.drawable.ic_chevron else R.drawable.ic_check)
        check.visibility = if (option.checked || option.opens != null) View.VISIBLE else View.INVISIBLE
    }
}
