package com.thefactor1.player.home

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thefactor1.player.R
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.hub.HubResume
import com.thefactor1.player.hub.HubStream
import com.thefactor1.player.hub.NextEpisode
import com.thefactor1.player.hub.Video
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The title page (Details): over the title's backdrop, its poster, logo or name, the year, runtime
 * and genres, the best copy's badges, the overview, then Play, Other versions and Add to library
 * (or "In your library"). A show also gets its seasons and episodes on the right, each playable.
 */
class TitlePage(
    private val root: FrameLayout,
    private val fonts: Fonts,
    /** Plays [HubItem] (the given episode of a show, or the next one when null); the view is the control pressed. */
    private val play: (HubItem, NextEpisode?, TextView) -> Unit,
    private val versions: (HubItem) -> Unit,
    private val keep: (HubItem) -> Unit,
    /** "Add to collection": the caller asks which one. */
    private val collect: (HubItem) -> Unit,
    /** Where this box left an episode, from its own resume memory. */
    private val episodeResume: (HubItem, NextEpisode) -> HubResume?,
) {
    private val context = root.context
    private lateinit var info: LinearLayout
    private lateinit var poster: PosterView
    private lateinit var logo: ImageView
    private lateinit var name: TextView
    private lateinit var credits: TextView
    private lateinit var chips: LinearLayout
    private lateinit var overview: TextView
    private lateinit var playButton: TextView
    private lateinit var keepButton: TextView
    private lateinit var owned: TextView
    private lateinit var franchise: LinearLayout
    private lateinit var franchiseLabel: TextView
    private lateinit var franchiseParts: LinearLayout
    private lateinit var pinButton: TextView
    private lateinit var episodesPanel: LinearLayout
    private lateinit var seasonTabs: LinearLayout
    private lateinit var episodes: LinearLayout
    private lateinit var episodesNote: TextView
    private var season = -1
    /** The best copy's badges have arrived; the item's own (older) ones no longer stand in. */
    private var streamShown = false

    var item: HubItem? = null
        private set

    /** Set at once, not when the fade ends, so Home's keys follow a close straight away. */
    var isOpen = false
        private set

    fun open(item: HubItem) {
        if (root.childCount == 0) build()
        isOpen = true
        season = -1
        streamShown = false
        franchise.visibility = View.GONE
        logo.visibility = View.GONE
        name.visibility = View.VISIBLE
        seasonTabs.removeAllViews()
        episodes.removeAllViews()
        show(item)
        root.animate().cancel()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.translationY = Mercury.dp(root, Motion.RISE_DP).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        playButton.requestFocus()
    }

    fun close() {
        isOpen = false
        item = null
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit)
            .withEndAction { root.visibility = View.GONE }.start()
    }

    /** Fresh details for the title on screen (the full record arrived, or it was added). */
    fun show(item: HubItem) {
        this.item = item
        val width = (root.resources.displayMetrics.widthPixels * (if (item.isSeries) SERIES_INFO_WIDTH else INFO_WIDTH)).toInt()
        info.layoutParams = info.layoutParams.apply { this.width = width }
        poster.bind(item, Mercury.dp(root, POSTER_W_DP * 2))
        name.text = item.title
        credits.text = listOfNotNull(item.year?.toString(), Mercury.length(item), item.genres.take(3).joinToString(", ").ifEmpty { null })
            .joinToString(" · ").uppercase()
        if (!streamShown) showChips(item.badges.all() + listOfNotNull(item.badges.mbps?.let { "${it.roundToInt()} MBPS" }))
        overview.text = item.overview
        overview.visibility = if (item.overview.isNullOrEmpty()) View.GONE else View.VISIBLE
        val episode = HomePlay.episodeFor(item)
        val resuming = Mercury.progress(item) != null
        playButton.text = when {
            item.isSeries && episode != null ->
                context.getString(if (resuming) R.string.home_resume_episode else R.string.home_play_episode, episode.season, episode.episode)
            else -> context.getString(if (resuming) R.string.home_resume else R.string.home_play)
        }
        val keepHadFocus = keepButton.isFocused
        keepButton.visibility = if (item.inLibrary) View.GONE else View.VISIBLE
        owned.visibility = if (item.inLibrary) View.VISIBLE else View.GONE
        if (keepHadFocus && item.inLibrary) playButton.requestFocus()

        episodesPanel.visibility = if (item.isSeries) View.VISIBLE else View.GONE
        if (item.isSeries) showSeasons(item)
    }

    /** The best copy's badges from `/browse/streams`: what Play will actually get. */
    fun showStream(best: HubStream?) {
        best ?: return
        streamShown = true
        showChips(listOfNotNull(best.badges.res, best.badges.hdr, best.badges.audio, best.origin,
            best.sizeGb?.let { String.format(Locale.US, "%.1f GB", it) }, best.badges.mbps?.let { "${it.roundToInt()} MBPS" }))
    }

    /**
     * The series [item] belongs to: each part a small poster (OK opens it), this one marked, and
     * Pin to Home to make the series a row.
     */
    fun showFranchise(item: HubItem, title: String, parts: List<HubItem>, open: (HubItem) -> Unit, pin: () -> Unit) {
        if (this.item?.id != item.id || parts.size < 2) return
        franchiseLabel.text = context.getString(R.string.home_franchise, title).uppercase()
        franchiseParts.removeAllViews()
        parts.forEach { part ->
            val poster = PosterView(context).apply {
                isFocusable = true
                isClickable = true
                bind(part, Mercury.dp(root, PART_W_DP * 2))
                // This film wears the light edge; the others take it only while focused.
                val edge = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Mercury.dp(root, 8f).toFloat()
                    setStroke(Mercury.dp(root, 2f), context.getColor(R.color.card_edge))
                }
                if (part.id == item.id) foreground = edge
                setOnFocusChangeListener { v, has ->
                    if (part.id != item.id) (v as PosterView).foreground = if (has) edge else null
                    v.animate().scaleX(if (has) PART_LIFT else 1f).scaleY(if (has) PART_LIFT else 1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.enter).start()
                }
                setOnClickListener { if (part.id != item.id) open(part) }
            }
            franchiseParts.addView(poster, LinearLayout.LayoutParams(Mercury.dp(root, PART_W_DP), Mercury.dp(root, PART_W_DP * 3 / 2)).apply {
                marginEnd = Mercury.dp(root, 8f)
            })
        }
        pinButton.setOnClickListener { pin() }
        franchise.visibility = View.VISIBLE
    }

    fun showLogo(bitmap: Bitmap) {
        logo.setImageBitmap(bitmap)
        logo.alpha = 0f
        logo.visibility = View.VISIBLE
        logo.animate().alpha(1f).setDuration(Motion.ENTER_MS).start()
        name.visibility = View.GONE
    }

    private fun showChips(labels: List<String>) {
        chips.removeAllViews()
        labels.forEach { label ->
            chips.addView(Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_fg, 0.16f).apply {
                text = label.uppercase()
                setBackgroundResource(R.drawable.mercury_chip)
                setPadding(Mercury.dp(root, 7f), Mercury.dp(root, 3f), Mercury.dp(root, 7f), Mercury.dp(root, 3f))
            }, wrap(end = 5f))
        }
        chips.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- Seasons and episodes -------------------------------------------------------------------

    private fun showSeasons(item: HubItem) {
        if (item.videos.isEmpty()) {
            episodesNote.setText(R.string.home_loading)
            episodesNote.visibility = View.VISIBLE
            return
        }
        episodesNote.visibility = View.GONE
        // Specials (season 0) go last, after the real seasons.
        val seasons = item.videos.map { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
        if (season !in seasons) season = item.nextEpisode?.season?.takeIf { it in seasons } ?: seasons.first()
        seasonTabs.removeAllViews()
        seasons.forEach { number ->
            val tab = Mercury.text(context, fonts.ui, 15f).apply {
                text = if (number == 0) context.getString(R.string.home_specials) else context.getString(R.string.home_season, number)
                tag = number
                setPadding(0, Mercury.dp(root, 4f), 0, Mercury.dp(root, 6f))
                isFocusable = true
                isClickable = true
                // Moving along the seasons shows each one's episodes; no OK needed.
                setOnFocusChangeListener { _, has ->
                    if (has && season != number) {
                        season = number
                        showEpisodes(item)
                    }
                    paintSeasons()
                }
            }
            seasonTabs.addView(tab, wrap(end = 22f))
        }
        paintSeasons()
        showEpisodes(item)
    }

    private fun paintSeasons() {
        for (i in 0 until seasonTabs.childCount) {
            val tab = seasonTabs.getChildAt(i)
            val active = tab.tag == season
            tab.alpha = if (active || tab.isFocused) 1f else Mercury.TAB_REST
            tab.background = if (active) Mercury.meniscus(context) else null
        }
    }

    private fun showEpisodes(item: HubItem) {
        episodes.removeAllViews()
        item.videos.filter { it.season == season }.forEach { episodes.addView(episodeRow(item, it)) }
    }

    private fun episodeRow(item: HubItem, video: Video): View {
        val episode = NextEpisode(video.season, video.episode, video.title)
        val isNext = item.nextEpisode?.let { it.season == video.season && it.episode == video.episode } == true
        // The hub's shared point belongs to the next-up episode; the others use this box's own memory.
        val resume = episodeResume(item, episode) ?: item.resume.takeIf { isNext }
        val fraction = resume?.takeIf { it.durationMs > 0 && it.positionMs > 0 }?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            alpha = EPISODE_REST
            setPadding(Mercury.dp(root, 6f), Mercury.dp(root, 6f), Mercury.dp(root, 6f), Mercury.dp(root, 6f))
            setOnFocusChangeListener { v, has ->
                v.background = if (has) context.getDrawable(R.drawable.mercury_hover) else null
                v.animate().alpha(if (has) 1f else EPISODE_REST).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.enter).start()
            }
        }
        val thumb = PosterView(context, backdrop = true)
        thumb.bind(HomeArt.episode(item, video.season, video.episode, video.thumbnail), fraction, Mercury.dp(root, THUMB_W_DP))
        row.addView(thumb, LinearLayout.LayoutParams(Mercury.dp(root, THUMB_W_DP), Mercury.dp(root, THUMB_W_DP * 9 / 16)).apply {
            marginEnd = Mercury.dp(root, 14f)
        })
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = Mercury.text(context, fonts.ui, 15f).apply {
            text = listOfNotNull("${video.episode}", video.title).joinToString("  ·  ")
            maxLines = 2
        }
        words.addView(title)
        val left = resume?.takeIf { fraction != null }?.let { ((it.durationMs - it.positionMs) / 60_000).coerceAtLeast(1) }
        val status = listOfNotNull(
            if (isNext) context.getString(R.string.home_next_up) else null,
            left?.let { "$it MIN LEFT" },
            if (!isNext && left == null) video.released else null,
        ).joinToString(" · ")
        if (status.isNotEmpty()) {
            words.addView(Mercury.text(context, fonts.mercuryMono, 10f, if (isNext) R.color.mercury_blue else R.color.mercury_muted, 0.14f)
                .apply { text = status }, wrap(top = 3f))
        }
        video.overview?.let { text ->
            words.addView(Mercury.text(context, fonts.ui, 12f, R.color.mercury_muted).apply {
                this.text = text
                maxLines = 2
            }, wrap(top = 3f))
        }
        row.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { play(item, episode, title) }
        return row
    }

    // ---- Views ----------------------------------------------------------------------------------

    private fun build() {
        val screenWidth = root.resources.displayMetrics.widthPixels
        // The poster beside the words, and the buttons under both, so four buttons fit beside a show's episodes.
        info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(Mercury.dp(root, Mercury.SAFE_X), 0, Mercury.dp(root, 16f), Mercury.dp(root, 40f))
        }
        root.addView(info, FrameLayout.LayoutParams((screenWidth * INFO_WIDTH).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        val top = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        info.addView(top)
        poster = PosterView(context)
        top.addView(poster, LinearLayout.LayoutParams(Mercury.dp(root, POSTER_W_DP), Mercury.dp(root, POSTER_W_DP * 3 / 2)).apply {
            marginEnd = Mercury.dp(root, 24f)
            bottomMargin = Mercury.dp(root, 4f)
        })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        top.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logo = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            maxWidth = Mercury.dp(root, 380f)
            maxHeight = Mercury.dp(root, 100f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // "In your library" sits over the title, where it can't crowd the buttons off the line.
        owned = Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.22f).apply { setText(R.string.home_in_library) }
        column.addView(owned, wrap(bottom = 10f))
        column.addView(logo, wrap(bottom = 12f))
        name = Mercury.title(context, fonts, 40f).apply { maxLines = 2 }
        column.addView(name, wrap(bottom = 10f))
        credits = Mercury.text(context, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.14f)
        column.addView(credits, wrap(bottom = 10f))
        chips = LinearLayout(context)
        column.addView(chips, wrap(bottom = 12f))
        overview = Mercury.text(context, fonts.ui, 14f).apply {
            maxLines = 4
            alpha = OVERVIEW_ALPHA
            setLineSpacing(0f, 1.4f)
        }
        column.addView(overview)

        // A film's series, in order, with this one marked; under the overview, over the buttons.
        franchise = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        column.addView(franchise, wrap(top = 14f))
        franchiseLabel = Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.2f)
        franchise.addView(franchiseLabel, wrap(bottom = 8f))
        val strip = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        franchise.addView(strip)
        franchiseParts = LinearLayout(context)
        strip.addView(franchiseParts)
        pinButton = button(R.string.home_pin) {}
        pinButton.height = Mercury.dp(root, 34f)
        strip.addView(pinButton, wrap().apply { marginStart = Mercury.dp(root, 8f) })
        Mercury.liftRoom(strip, levels = 3)

        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        info.addView(buttons, wrap(top = 18f))
        playButton = button(R.string.home_play) { item?.let { play(it, null, playButton) } }
        buttons.addView(playButton, wrap(end = 10f))
        buttons.addView(button(R.string.home_versions) { item?.let(versions) }, wrap(end = 10f))
        keepButton = button(R.string.home_keep) { item?.let(keep) }
        buttons.addView(keepButton, wrap(end = 10f))
        buttons.addView(button(R.string.home_collect) { item?.let(collect) })

        // A show's episodes: a quiet panel on the right, seasons as text tabs over the list.
        episodesPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.mercury_glass_plain))
            setPadding(Mercury.dp(root, 24f), Mercury.dp(root, 40f), Mercury.dp(root, Mercury.SAFE_X), 0)
        }
        root.addView(episodesPanel, FrameLayout.LayoutParams((screenWidth * EPISODES_WIDTH).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        episodesPanel.addView(Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.22f).apply { setText(R.string.home_episodes) })
        val tabsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        seasonTabs = LinearLayout(context)
        tabsScroll.addView(seasonTabs)
        episodesPanel.addView(tabsScroll, wrap(top = 10f, bottom = 8f))
        episodesNote = Mercury.text(context, fonts.mercuryMono, 10f, R.color.mercury_muted, 0.2f)
        episodesPanel.addView(episodesNote)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            // Room below the last episode for its highlight and the safe area.
            setPadding(0, Mercury.dp(root, 4f), 0, Mercury.dp(root, Mercury.SAFE_Y + 12f))
        }
        episodes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(episodes)
        episodesPanel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        Mercury.liftRoom(buttons, levels = 2)
    }

    private fun button(label: Int, click: () -> Unit) =
        TextView(context, null, 0, R.style.ControlButton).apply {
            setText(label)
            typeface = fonts.ui
            textSize = 15f
            height = Mercury.dp(root, 42f)
            setPadding(Mercury.dp(root, 20f), 0, Mercury.dp(root, 20f), 0)
            setOnClickListener { click() }
        }

    private fun wrap(top: Float = 0f, bottom: Float = 0f, end: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Mercury.dp(root, top)
            bottomMargin = Mercury.dp(root, bottom)
            marginEnd = Mercury.dp(root, end)
        }

    private companion object {
        const val INFO_WIDTH = 0.66f
        const val SERIES_INFO_WIDTH = 0.6f
        const val EPISODES_WIDTH = 0.38f
        const val POSTER_W_DP = 132f
        const val THUMB_W_DP = 104f
        const val OVERVIEW_ALPHA = 0.8f
        const val EPISODE_REST = 0.72f
        const val PART_W_DP = 52f
        const val PART_LIFT = 1.06f
    }
}
