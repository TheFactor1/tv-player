package com.thefactor1.player.home

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.meta.ArtworkLoader
import com.thefactor1.player.ui.FocusGlide
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion

/**
 * Search without a keyboard: a large field, a d-pad letter grid on the left, and live results from
 * the hub on the right as a two-column grid, library titles first and tagged.
 */
class SearchScreen(
    private val root: FrameLayout,
    private val fonts: Fonts,
    private val art: ArtworkLoader,
    private val search: (String, (Result<List<HubItem>>) -> Unit) -> Unit,
    private val onFocusItem: (HubItem) -> Unit,
    private val onOpen: (HubItem) -> Unit,
) {
    private val context = root.context
    private val main = Handler(Looper.getMainLooper())
    private var query = ""
    private lateinit var field: TextView
    private lateinit var hint: TextView
    private lateinit var results: LinearLayout
    private lateinit var glide: FocusGlide
    private var lastKey: View? = null
    private val runSearch = Runnable { fetch() }

    /** Set at once, not when the fade ends, so Home's tabs and keys follow a close straight away. */
    var isOpen = false
        private set

    fun open() {
        if (root.childCount == 0) build()
        isOpen = true
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        (lastKey ?: root.findViewWithTag<View>(KEY_A))?.requestFocus()
    }

    fun close() {
        isOpen = false
        main.removeCallbacks(runSearch)
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit)
            .withEndAction { root.visibility = View.GONE }.start()
    }

    /** Back inside Search: from the results to the keys. False when Back should close Search. */
    fun back(): Boolean {
        val focused = root.findFocus() ?: return false
        if (!isDescendant(focused, results)) return false
        (lastKey ?: root.findViewWithTag<View>(KEY_A))?.requestFocus()
        return true
    }

    /** A letter, digit or space from a keyboard or phone remote app; backspace is [type] with null. */
    fun type(char: Char?) {
        query = when {
            char == null -> query.dropLast(1)
            query.length >= MAX_LENGTH -> query
            char == ' ' && (query.isEmpty() || query.endsWith(' ')) -> query
            else -> query + char.lowercaseChar()
        }
        showQuery()
        main.removeCallbacks(runSearch)
        main.postDelayed(runSearch, DEBOUNCE_MS)
    }

    private fun build() {
        val width = root.resources.displayMetrics.widthPixels
        val row = LinearLayout(context)
        root.addView(row)

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Mercury.dp(root, Mercury.SAFE_X), 0, 0, 0)
        }
        row.addView(left, LinearLayout.LayoutParams((width * LEFT_WIDTH).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))

        val fieldRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        field = Mercury.text(context, fonts.display, 29f, tracking = -0.03f)
        fieldRow.addView(field)
        fieldRow.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.mercury_fg)) },
            LinearLayout.LayoutParams(Mercury.dp(root, 2f), Mercury.dp(root, 26f)).apply { marginStart = Mercury.dp(root, 2f) })
        left.addView(fieldRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Mercury.dp(root, 44f)))
        left.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.mercury_faint)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Mercury.dp(root, 1f)).apply { bottomMargin = Mercury.dp(root, 10f) })

        val keyFrame = FrameLayout(context)
        val hover = View(context).apply { setBackgroundResource(R.drawable.mercury_hover); visibility = View.INVISIBLE }
        keyFrame.addView(hover, FrameLayout.LayoutParams(0, 0))
        glide = FocusGlide(hover) { key, amount -> key.alpha = KEY_REST + (1f - KEY_REST) * amount }
        val keys = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        keyFrame.addView(keys)
        left.addView(keyFrame)
        val letters = ('A'..'Z') + ('0'..'9')
        letters.chunked(KEYS_PER_ROW).forEach { chars ->
            val line = LinearLayout(context)
            chars.forEach { char -> line.addView(key(char.toString(), KEY_A.takeIf { char == 'A' }) { type(char) }, weight(1f)) }
            keys.addView(line)
        }
        val last = LinearLayout(context)
        last.addView(key(context.getString(R.string.search_space), mono = true) { type(' ') }, weight(4f))
        last.addView(key("⌫") { type(null) }, weight(2f))
        keys.addView(last)

        left.addView(Mercury.text(context, fonts.mercuryMono, 9f, R.color.mercury_muted, 0.2f).apply { setText(R.string.search_phone) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Mercury.dp(root, 14f) })

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Mercury.dp(root, 24f), Mercury.dp(root, 6f), Mercury.dp(root, Mercury.SAFE_X), 0)
        }
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        hint = Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.18f)
        right.addView(hint)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(Mercury.dp(root, 6f), Mercury.dp(root, 8f), Mercury.dp(root, 6f), Mercury.dp(root, Mercury.SAFE_Y))
        }
        right.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        showQuery()
    }

    private fun key(label: String, tag: String? = null, mono: Boolean = false, press: () -> Unit) =
        Mercury.text(context, if (mono) fonts.mercuryMono else fonts.ui, if (mono) 10f else 15f, tracking = if (mono) 0.24f else 0f).apply {
            text = label
            this.tag = tag
            gravity = Gravity.CENTER
            alpha = KEY_REST
            isFocusable = true
            isClickable = true
            minHeight = Mercury.dp(root, KEY_HEIGHT_DP)
            setOnClickListener { press() }
            setOnFocusChangeListener { v, has ->
                if (has) {
                    lastKey = v
                    glide.moveTo(v)
                }
            }
        }

    private fun showQuery() {
        field.text = query.ifEmpty { context.getString(R.string.home_search) }
        field.alpha = if (query.isEmpty()) PLACEHOLDER_ALPHA else 1f
        if (query.isBlank()) {
            hint.setText(R.string.search_try)
            results.removeAllViews()
        }
    }

    private fun fetch() {
        val asked = query.trim()
        if (asked.isEmpty()) return
        search(asked) done@{ result ->
            if (asked != query.trim()) return@done
            val items = result.getOrNull()
            hint.text = when {
                items == null -> result.exceptionOrNull()?.message.orEmpty().uppercase()
                items.isEmpty() -> context.getString(R.string.search_none, asked.uppercase())
                else -> context.resources.getQuantityString(R.plurals.search_results, items.size, items.size)
            }
            showResults(items.orEmpty())
        }
    }

    private fun showResults(items: List<HubItem>) {
        // Rebuilt from scratch, so a key keeps focus while results change under it.
        results.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val cardWidth = (root.resources.displayMetrics.widthPixels * (1 - LEFT_WIDTH) - Mercury.dp(root, 24f + Mercury.SAFE_X + 12f + 18f)).toInt() / 2
        items.chunked(2).forEach { pair ->
            val line = LinearLayout(context)
            pair.forEachIndexed { index, item ->
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val card = inflater.inflate(R.layout.item_home_card, cell, false)
                card.layoutParams = LinearLayout.LayoutParams(cardWidth, cardWidth * 9 / 16)
                card.clipToOutline = true
                card.findViewById<View>(R.id.card_progress).visibility = View.GONE
                card.findViewById<TextView>(R.id.card_title).apply {
                    typeface = fonts.ui
                    text = item.title
                }
                val image = card.findViewById<ImageView>(R.id.card_art)
                art.backdrop(item.id, cardWidth) { image.setImageBitmap(it) }
                card.setOnClickListener { onOpen(item) }
                card.setOnFocusChangeListener { v, has ->
                    if (has) onFocusItem(item)
                    val scale = if (has) CARD_LIFT else 1f
                    v.animate().scaleX(scale).scaleY(scale).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.enter).start()
                }
                cell.addView(card)
                if (item.inLibrary) {
                    cell.addView(Mercury.text(context, fonts.mercuryMono, 9f, R.color.mercury_muted, 0.14f).apply { setText(R.string.home_in_library) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Mercury.dp(root, 6f) })
                }
                line.addView(cell, LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index == 0) marginEnd = Mercury.dp(root, 18f)
                    bottomMargin = Mercury.dp(root, 14f)
                })
            }
            results.addView(line)
        }
    }

    private fun isDescendant(view: View, parent: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun weight(value: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, value)

    private companion object {
        const val LEFT_WIDTH = 0.38f
        const val KEYS_PER_ROW = 6
        const val KEY_HEIGHT_DP = 30f
        const val KEY_REST = 0.72f
        const val PLACEHOLDER_ALPHA = 0.3f
        const val CARD_LIFT = 1.03f
        const val MAX_LENGTH = 24
        const val DEBOUNCE_MS = 300L
        const val KEY_A = "search-key-a"
    }
}
