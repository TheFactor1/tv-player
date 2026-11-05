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
    /** A page one step in, opened with OK, e.g. "More". */
    val opens: String? = null,
    /** What the preview beside the list shows while this row has focus. */
    val preview: Preview = Preview.NONE,
    val action: () -> Unit = {},
)

/** The small scene beside the settings list: what the focused setting changes. */
enum class Preview { NONE, SUBTITLES, SOUND, SHAPE }

class Steps(val index: Int, val count: Int, val set: (Int) -> Unit)

/**
 * The settings list, built the same way as the player menus: one line per setting, values stepped
 * in place with Left and Right. A row can open a page one step in ([Option.opens]); [back] returns.
 * [onFocus] hears about the focused row, and again whenever its value changes, so the preview and
 * its words follow. Rows are rebuilt only by [show]; a change redraws its own row, so focus never jumps.
 */
class OptionList(
    private val context: Context,
    private val container: LinearLayout,
    private val fonts: Fonts,
    private val rowsOf: (page: String) -> List<RowSpec>,
    private val onFocus: (Option) -> Unit,
    private val onPage: (page: String) -> Unit,
) {
    /** A row's option, built again from the settings whenever the row is redrawn. */
    sealed class RowSpec {
        class Item(val option: () -> Option) : RowSpec()
    }

    private val density = context.resources.displayMetrics.density

    // The pages open now, the first page first, and the focused row in each page left behind.
    private val trail = mutableListOf(ROOT)
    private val trailFocus = mutableListOf<Int>()

    /** On the first page, where Back closes the settings rather than stepping out. */
    val isTop: Boolean get() = trail.size == 1

    /** Back to the first page, as when the settings open. */
    fun reset() {
        trail.clear()
        trail += ROOT
        trailFocus.clear()
    }

    /** One page back, landing on the row that opened it. False on the first page. */
    fun back(): Boolean {
        if (isTop) return false
        trail.removeAt(trail.lastIndex)
        show(trailFocus.removeAt(trailFocus.lastIndex))
        return true
    }

    private fun open(page: String) {
        trailFocus += focusedIndex()
        trail += page
        show()
    }

    /** Builds the page's rows and focuses the one at [focusIndex] (an index into its rows), or the first. */
    fun show(focusIndex: Int = -1) {
        container.removeAllViews()
        onPage(trail.last())
        val inflater = LayoutInflater.from(context)
        val rows = mutableListOf<View>()
        var target: View? = null
        rowsOf(trail.last()).forEachIndexed { index, spec ->
            when (spec) {
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
                        val opens = option.opens
                        when {
                            opens != null -> open(opens)
                            // OK runs through the choices too, wrapping at the end.
                            steps != null -> steps.set((steps.index + 1).mod(steps.count))
                            else -> option.action()
                        }
                        if (opens == null) {
                            bind(row, spec.option())
                            if (row.hasFocus()) onFocus(row.tag as Option)
                        }
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
            onFocus(it.tag as Option)
        }
    }

    /** The page's index of the focused row, to land on it again after a rebuild. */
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
            onFocus(row.tag as Option)
        }
        return true
    }

    /** Keeps the focused row in the middle of the list when a page is longer than the screen. */
    private fun onFocused(row: View) {
        onFocus(row.tag as Option)
        val scroller = container.parent as? ScrollView ?: return
        row.post {
            val y = container.top + row.top - (scroller.height - row.height) / 2
            scroller.smoothScrollTo(0, y.coerceAtLeast(0))
        }
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
        more.visibility = if (steps != null || option.opens != null) View.VISIBLE else View.GONE
        // At either end of a range that arrow fades: there is nowhere further to go.
        less.alpha = if (steps != null && steps.index == 0) END_ARROW_ALPHA else 1f
        more.alpha = if (steps != null && steps.index == steps.count - 1) END_ARROW_ALPHA else 1f
        val switch = row.findViewById<SwitchMark>(R.id.item_switch)
        switch.visibility = if (option.switch) View.VISIBLE else View.GONE
        switch.on = option.checked
        row.findViewById<ImageView>(R.id.item_check).visibility = View.GONE
    }

    companion object {
        /** The page the settings open on. */
        const val ROOT = "main"
        private const val END_ARROW_ALPHA = 0.3f
    }
}
