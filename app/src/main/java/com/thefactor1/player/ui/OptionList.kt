package com.thefactor1.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thefactor1.player.R

/** One line of the home settings panel: a setting stepped in place, a switch, an action, or a fact. */
class Option(
    val label: String,
    /** Beside the name: the current value, or a fact about this TV. */
    val value: String? = null,
    /** Under the list while the row has focus. */
    val hint: String? = null,
    /** Drawn as an on/off switch; OK flips it. */
    val switch: Boolean = false,
    val checked: Boolean = false,
    /** A setting with a range of values, stepped with Left and Right on its own row. */
    val steps: Steps? = null,
    val action: () -> Unit = {},
)

class Steps(val index: Int, val count: Int, val set: (Int) -> Unit)

/**
 * The settings panel's list, built the same way as the player menus: one line per setting, values
 * stepped in place with Left and Right, and a hint line under the list for the focused row. Rows
 * are rebuilt only by [show]; a change redraws its own row, so focus never jumps.
 */
class OptionList(
    private val context: Context,
    private val container: LinearLayout,
    private val hint: TextView,
    private val fonts: Fonts,
    private val root: () -> List<RowSpec>,
) {
    /** A row: a section heading, or an option (rebuilt from scratch on refresh). */
    sealed class RowSpec {
        class Heading(val text: String) : RowSpec()
        class Item(val option: () -> Option) : RowSpec()
    }

    private val density = context.resources.displayMetrics.density

    /** Builds the rows and focuses the one at [focusIndex] (an index into [root]), or the first. */
    fun show(focusIndex: Int = -1) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val rows = mutableListOf<View>()
        var target: View? = null
        root().forEachIndexed { index, spec ->
            when (spec) {
                is RowSpec.Heading -> container.addView(heading(spec.text, first = index == 0))
                is RowSpec.Item -> {
                    val row = inflater.inflate(R.layout.item_menu_row, container, false)
                    row.findViewById<TextView>(R.id.item_label).typeface = fonts.semibold
                    row.findViewById<TextView>(R.id.item_value).typeface = fonts.semibold
                    row.setTag(R.id.item_label, spec)
                    row.setTag(R.id.item_value, index)
                    bind(row, spec.option())
                    row.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) onFocused(v) }
                    row.setOnClickListener {
                        val option = spec.option()
                        val steps = option.steps
                        // OK runs through the choices too, wrapping at the end.
                        if (steps != null) steps.set((steps.index + 1).mod(steps.count)) else option.action()
                        bind(row, spec.option())
                        if (row.hasFocus()) hint.text = (row.tag as Option).hint
                    }
                    row.id = View.generateViewId()
                    (row.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (2 * density).toInt()
                    container.addView(row)
                    rows += row
                    if (index == focusIndex) target = row
                }
            }
        }
        // Keep focus inside the list at both ends; Left and Right step values instead of moving.
        rows.firstOrNull()?.let { it.nextFocusUpId = it.id }
        rows.lastOrNull()?.let { it.nextFocusDownId = it.id }
        rows.forEach { it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        (target ?: rows.firstOrNull())?.let {
            it.requestFocus()
            it.jumpDrawablesToCurrentState()
            hint.text = (it.tag as Option).hint
        }
    }

    /** The [root] index of the focused row, to land on it again after a rebuild. */
    fun focusedIndex(): Int = container.focusedChild?.getTag(R.id.item_value) as? Int ?: -1

    /** Left or Right on a stepped setting. False when the focused row isn't one. */
    fun step(delta: Int): Boolean {
        val row = container.focusedChild ?: return false
        val spec = row.getTag(R.id.item_label) as? RowSpec.Item ?: return false
        val steps = spec.option().steps ?: return false
        val next = (steps.index + delta).coerceIn(0, steps.count - 1)
        if (next != steps.index) {
            steps.set(next)
            bind(row, spec.option())
            hint.text = (row.tag as Option).hint
        }
        return true
    }

    /** Keeps the focused row in the middle of the list, so the heading above it stays in view near the top. */
    private fun onFocused(row: View) {
        hint.text = (row.tag as Option).hint
        val scroller = container.parent as? ScrollView ?: return
        row.post {
            val y = container.top + row.top - (scroller.height - row.height) / 2
            scroller.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    private fun heading(text: String, first: Boolean) = TextView(context).apply {
        this.text = text.uppercase()
        typeface = fonts.semibold
        textSize = 12f
        letterSpacing = 0.12f
        setTextColor(context.getColor(R.color.ink_secondary))
        val side = (16 * density).toInt()
        setPadding(side, ((if (first) 2 else 18) * density).toInt(), side, (6 * density).toInt())
    }

    private fun bind(row: View, option: Option) {
        row.tag = option
        row.findViewById<TextView>(R.id.item_label).text = option.label
        val value = row.findViewById<TextView>(R.id.item_value)
        value.text = option.value
        value.visibility = if (option.value.isNullOrEmpty()) View.GONE else View.VISIBLE
        val steps = option.steps
        val less = row.findViewById<ImageView>(R.id.item_less)
        val more = row.findViewById<ImageView>(R.id.item_more)
        less.visibility = if (steps != null) View.VISIBLE else View.GONE
        more.visibility = if (steps != null) View.VISIBLE else View.GONE
        // At either end of a range that arrow fades: there is nowhere further to go.
        less.alpha = if (steps != null && steps.index == 0) END_ARROW_ALPHA else 1f
        more.alpha = if (steps != null && steps.index == steps.count - 1) END_ARROW_ALPHA else 1f
        val switch = row.findViewById<SwitchMark>(R.id.item_switch)
        switch.visibility = if (option.switch) View.VISIBLE else View.GONE
        switch.on = option.checked
        row.findViewById<ImageView>(R.id.item_check).visibility = View.GONE
    }

    private companion object {
        const val END_ARROW_ALPHA = 0.3f
    }
}
