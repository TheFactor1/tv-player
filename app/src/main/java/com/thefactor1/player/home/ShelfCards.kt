package com.thefactor1.player.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion

/**
 * A poster card for Home's rows and grids: the picture (with progress), the title, one mono line.
 * Focus grows the picture 6 % with the theme's halo and a light edge; the words stay put.
 */
class PosterCard(context: Context, fonts: Fonts, private val widthPx: Int) : LinearLayout(context) {
    val poster = PosterView(context)
    private val title = Mercury.text(context, fonts.ui, 13f)
    private val line = Mercury.text(context, fonts.mercuryMono, 9f, R.color.mercury_muted, 0.12f)
    var item: HubItem? = null
        private set

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        addView(poster, LayoutParams(widthPx, widthPx * 3 / 2))
        addView(title, LayoutParams(widthPx, LayoutParams.WRAP_CONTENT).apply { topMargin = Mercury.dp(this@PosterCard, 9f) })
        addView(line, LayoutParams(widthPx, LayoutParams.WRAP_CONTENT).apply { topMargin = Mercury.dp(this@PosterCard, 4f) })
        val glow = Mercury.color(context, if (Mercury.space) R.color.mercury_blue else R.color.mercury_fg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Space glows cold; Mercury's halo is a chrome edge, so its shadow stays neutral.
            poster.outlineSpotShadowColor = glow
            poster.outlineAmbientShadowColor = glow
        }
        val edge = GradientDrawable().apply {
            cornerRadius = Mercury.dp(this@PosterCard, 8f).toFloat()
            setStroke((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(2), context.getColor(R.color.card_edge))
        }
        setOnFocusChangeListener { _, has ->
            poster.foreground = if (has) edge else null
            val scale = if (has) LIFT else 1f
            poster.animate().scaleX(scale).scaleY(scale).translationZ(if (has) Mercury.dp(this, HALO_DP).toFloat() else 0f)
                .setDuration(FOCUS_MS).setInterpolator(Motion.enter).start()
        }
    }

    fun bind(item: HubItem) {
        this.item = item
        poster.bind(item, widthPx)
        title.text = item.title
        line.text = Mercury.cardLine(item)
    }

    private companion object {
        const val LIFT = 1.06f
        const val HALO_DP = 14f
        const val FOCUS_MS = 180L
    }
}

/**
 * The cards of one row or one grid. [onNearEnd] asks for the next page when the last few are bound;
 * [onFocus] follows focus for the hero and the backdrop.
 */
class CardAdapter(
    private val fonts: Fonts,
    private val cardWidthPx: Int,
    private val gapPx: Int,
    private val onFocus: (HubItem, Int) -> Unit,
    private val onClick: (HubItem) -> Unit,
    var onNearEnd: (() -> Unit)? = null,
) : RecyclerView.Adapter<CardAdapter.Holder>() {
    /** Each card as it's bound, for filling in what the hub left out. */
    var onBound: ((HubItem) -> Unit)? = null
    class Holder(val card: PosterCard) : RecyclerView.ViewHolder(card)

    val items = mutableListOf<HubItem>()

    fun submit(list: List<HubItem>) {
        items.clear()
        items += list
        notifyDataSetChanged()
    }

    fun append(more: List<HubItem>) {
        val start = items.size
        items += more
        notifyItemRangeInserted(start, more.size)
    }

    /** A fuller or changed record for a title already shown. */
    fun replace(item: HubItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        items[index] = item
        notifyItemChanged(index)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = PosterCard(parent.context, fonts, cardWidthPx)
        card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(gapPx / 2, gapPx / 2, gapPx / 2, gapPx / 2)
        }
        val holder = Holder(card)
        card.setOnClickListener { card.item?.let(onClick) }
        card.setOnFocusChangeListener(chain(card.onFocusChangeListener) { has ->
            val position = holder.bindingAdapterPosition
            if (has && position != RecyclerView.NO_POSITION) onFocus(items[position], position)
        })
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.card.bind(items[position])
        onBound?.invoke(items[position])
        if (position >= items.size - PAGE_AHEAD) onNearEnd?.invoke()
    }

    private fun chain(first: View.OnFocusChangeListener?, then: (Boolean) -> Unit) =
        View.OnFocusChangeListener { v, has ->
            first?.onFocusChange(v, has)
            then(has)
        }

    private companion object {
        const val PAGE_AHEAD = 6
    }
}

/** One of Home's rows: its label and its cards; [more] fetches the next page, or null when a row comes whole. */
class ShelfRow(val label: String, val cards: CardAdapter, val more: ((Int) -> Unit)?)

/**
 * Home's rows: a label over a horizontal strip of cards each. Rows recycle their strips; a row's
 * cards live in its own adapter, so scrolling a row away and back keeps its place and its pages.
 */
class RowsAdapter(private val fonts: Fonts) : RecyclerView.Adapter<RowsAdapter.Holder>() {
    class Holder(val row: LinearLayout, val label: TextView, val strip: RecyclerView) : RecyclerView.ViewHolder(row)

    val rows = mutableListOf<ShelfRow>()
    private val pool = RecyclerView.RecycledViewPool()

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val label = Mercury.text(context, fonts.ui, 15f).apply {
            alpha = Mercury.TAB_REST
            setPadding(Mercury.dp(row, Mercury.SAFE_X), 0, 0, 0)
        }
        row.addView(label)
        val strip = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setRecycledViewPool(pool)
            clipToPadding = false
            clipChildren = false
            // Room for the focused card's growth and halo, and the safe area at the sides.
            val side = Mercury.dp(row, Mercury.SAFE_X) - Mercury.dp(row, GAP_DP) / 2
            setPadding(side, Mercury.dp(row, 6f), side, Mercury.dp(row, 10f))
            itemAnimator = null
        }
        row.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(row, label, strip)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val shelf = rows[position]
        holder.label.text = shelf.label
        if (holder.strip.adapter !== shelf.cards) holder.strip.swapAdapter(shelf.cards, false)
    }

    companion object {
        const val GAP_DP = 16f
    }
}

/** Keeps the focused row just under the hero, rather than scrolling only as far as needed. */
class TopAlignedLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val dy = child.top - parent.paddingTop
        if (dy == 0) return false
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
        return true
    }
}
