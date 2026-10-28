package com.thefactor1.player

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.home.CardAdapter
import com.thefactor1.player.home.Header
import com.thefactor1.player.home.HomeArt
import com.thefactor1.player.home.HomePlay
import com.thefactor1.player.home.Mercury
import com.thefactor1.player.home.PairingSetup
import com.thefactor1.player.home.PosterCard
import com.thefactor1.player.home.Refine
import com.thefactor1.player.home.RefineSheet
import com.thefactor1.player.home.RowsAdapter
import com.thefactor1.player.home.SearchScreen
import com.thefactor1.player.home.ShelfRow
import com.thefactor1.player.home.SpaceSky
import com.thefactor1.player.home.SpinnerDrawable
import com.thefactor1.player.home.StarLayer
import com.thefactor1.player.home.TitlePage
import com.thefactor1.player.home.TopAlignedLayoutManager
import com.thefactor1.player.home.VersionsSheet
import com.thefactor1.player.hub.Catalog
import com.thefactor1.player.hub.FoundHub
import com.thefactor1.player.hub.Franchise
import com.thefactor1.player.hub.HubCollection
import com.thefactor1.player.hub.HubClient
import com.thefactor1.player.hub.HubImages
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.hub.HubResume
import com.thefactor1.player.hub.HubStream
import com.thefactor1.player.hub.HubVault
import com.thefactor1.player.hub.NextEpisode
import com.thefactor1.player.hub.PlayInfo
import com.thefactor1.player.hub.Quality
import com.thefactor1.player.hub.Shelf
import com.thefactor1.player.launch.IntentContract
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.resume.ResumeStore
import com.thefactor1.player.ui.DefaultSettings
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion
import com.thefactor1.player.ui.Option
import com.thefactor1.player.ui.OptionList
import com.thefactor1.player.ui.PairingCard
import com.thefactor1.player.ui.ThisTv

/**
 * Home, the Shelf layout over the hub. Home itself is the focused title's hero over its backdrop
 * with rows of posters under it (Continue, Next Up, New, then each catalog); every other tab is a
 * grid of posters. Details, Search, Refine and Other versions open over it; the settings for every
 * video stay in the side panel. Without a hub it asks for one.
 */
class MainActivity : Activity() {

    private enum class Kind { HOME, LIBRARY, NEW, BROWSE }

    private class Tab(val label: Int, val kind: Kind, val collection: String? = null) {
        /** Continue and Next Up are where you left things; filtering them would hide what you're in the middle of. */
        val refinable: Boolean get() = kind == Kind.NEW || kind == Kind.BROWSE || collection == "movies" || collection == "shows"
    }

    /** One of Home's rows before and after it loads: where its titles come from, and the row once it has any. */
    private class Slot(
        val label: String,
        val paged: Boolean,
        /** The hub's row this is, when the hub keeps Home's rows (null on an older hub). */
        val shelf: Shelf? = null,
        /** Continue and Next Up: reloaded after a film or time away. */
        val refreshes: Boolean = false,
        val fetch: HubClient.(Int) -> List<HubItem>,
    ) {
        var row: ShelfRow? = null
        var loading = false
        var exhausted = false
    }

    private lateinit var fonts: Fonts
    private lateinit var prefs: PlayerPrefs
    private lateinit var vault: HubVault
    private val homePrefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val facts by lazy { ThisTv.facts(this, prefs) }
    private val main = Handler(Looper.getMainLooper())
    private var client: HubClient? = null

    private lateinit var artA: ImageView
    private lateinit var artB: ImageView
    private lateinit var fade: View
    private lateinit var body: View
    private lateinit var message: TextView
    private lateinit var hero: View
    private lateinit var heroButtons: LinearLayout
    private lateinit var playButton: TextView
    private lateinit var rowsView: RecyclerView
    private lateinit var gridBar: View
    private lateinit var catalogsScroll: View
    private lateinit var catalogsRow: LinearLayout
    private lateinit var whisper: TextView
    private lateinit var gridView: RecyclerView
    private lateinit var header: Header
    private lateinit var headerRoot: View
    private lateinit var setup: View
    private lateinit var pairing: PairingSetup
    private lateinit var panel: View
    private lateinit var settings: OptionList
    private lateinit var titlePage: TitlePage
    private lateinit var search: SearchScreen
    private lateinit var sheet: RefineSheet
    private lateinit var versions: VersionsSheet
    private lateinit var dim: View
    private lateinit var rows: RowsAdapter
    private lateinit var grid: CardAdapter

    private var tabIndex = 0
    private var catalogs: List<Catalog> = emptyList()
    private var catalogIndex = 0
    private var refine = Refine()
    private var slots: List<Slot> = emptyList()
    /** Everything loaded for the current grid tab, and the part of it the filters let through. */
    private var all: List<HubItem> = emptyList()
    private var shown: List<HubItem> = emptyList()
    private var loading = false
    private var exhausted = false
    /** Bumped on every new load, so an answer for a tab already left is dropped. */
    private var loadTicket = 0
    private var heroItem: HubItem? = null
    private var lastRowCard: View? = null
    /** Home has put focus on its first poster once; after that, rows arriving never move it. */
    private var homeFocusPlaced = false
    private var lastGridCard: View? = null
    private var artId: String? = null
    private var frontIsB = false
    private var panelList: OptionList? = null
    private var panelOpen = false
    private var beforePanel: View? = null
    private var beforeTitle: View? = null
    private var beforeVersions: View? = null
    /** Each title's ranked copies, fetched when its page opens and kept ten minutes, so Other versions opens at once. */
    private val streamCache = HashMap<String, Pair<Long, List<HubStream>>>()
    private val streamsAsked = HashSet<String>()
    /** A play is on its way to the hub: input is held until the player starts or it fails. */
    private var pending = false
    private var pendingButton: TextView? = null
    private var pendingLabel: CharSequence? = null
    /** What the player is playing for us, so its position goes back to the hub. */
    private var playing: Pair<String, NextEpisode?>? = null

    private val home get() = TABS[tabIndex].kind == Kind.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PlayerPrefs(this)
        Mercury.space = prefs.homeTheme == PlayerPrefs.THEME_SPACE
        setContentView(R.layout.activity_main)
        fonts = Fonts(this)
        vault = HubVault(this)
        artA = findViewById(R.id.home_art_a)
        artB = findViewById(R.id.home_art_b)
        fade = findViewById(R.id.home_fade)
        body = findViewById(R.id.home_body)
        message = findViewById(R.id.home_message)
        hero = findViewById(R.id.home_hero)
        heroButtons = findViewById(R.id.home_hero_buttons)
        playButton = findViewById(R.id.home_play)
        rowsView = findViewById(R.id.home_rows)
        gridBar = findViewById(R.id.home_grid_bar)
        catalogsScroll = findViewById(R.id.home_catalogs_scroll)
        catalogsRow = findViewById(R.id.home_catalogs)
        whisper = findViewById(R.id.home_whisper)
        gridView = findViewById(R.id.home_grid)
        headerRoot = findViewById(R.id.home_header)
        setup = findViewById(R.id.home_setup)
        panel = findViewById(R.id.settings_panel)
        dim = findViewById(R.id.home_dim)
        if (Mercury.space) addSky(fade)

        fonts.apply(fonts.ui, message, playButton, findViewById(R.id.home_details), findViewById(R.id.home_hero_overview))
        fonts.apply(fonts.mercuryMono, findViewById(R.id.home_hero_credits), findViewById(R.id.home_hero_kicker), whisper)
        fonts.apply(if (Mercury.space) fonts.spaceDisplay else fonts.display, findViewById(R.id.home_hero_title))
        findViewById<TextView>(R.id.home_hero_title).letterSpacing = if (Mercury.space) 0.05f else -0.03f
        listOf(R.id.home_hero_kicker).forEach { findViewById<TextView>(it).setTextColor(Mercury.color(this, R.color.mercury_blue)) }
        fonts.apply(fonts.bold, findViewById(R.id.settings_title))
        fonts.apply(fonts.semibold, findViewById(R.id.settings_kicker))
        fonts.apply(fonts.medium, findViewById(R.id.settings_hint))
        Mercury.liftRoom(heroButtons, levels = 2)

        header = Header(findViewById(R.id.home_header), fonts, TABS.map { it.label }, ::selectTab, ::openSearch, ::openRefine, ::openSettings)
        val defaults = DefaultSettings(this, prefs, { facts }) {
            closePanel()
            showSetup(rediscover = true)
        }
        pairing = PairingSetup(findViewById(R.id.home_setup), fonts, { address, token ->
            vault.address = address
            vault.token = token
            HubClient.clearCache()
            catalogs = emptyList()
            slots = emptyList()
            start()
        }, ::openSettings)
        settings = OptionList(this, findViewById(R.id.settings_items), findViewById(R.id.settings_hint), fonts, defaults::rows)
        titlePage = TitlePage(findViewById(R.id.home_title_page), fonts, { item, episode, pressed -> startPlay(item, null, episode, pressed) },
            ::showVersions, ::keep, ::addToCollection) { item, episode ->
            ResumeStore(this).find(listOf(HomePlay.localKey(item, episode, null)))?.let { HubResume(it.positionMs, it.durationMs, it.savedAt) }
        }
        versions = VersionsSheet(findViewById(R.id.home_versions), fonts, { stream, pressed ->
            val item = titlePage.item ?: return@VersionsSheet
            startPlay(item, stream, null, pressed)
        }) {
            findViewById<ViewGroup>(R.id.home_title_page).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            beforeVersions?.takeIf { it.isAttachedToWindow }?.requestFocus()
        }
        search = SearchScreen(findViewById(R.id.home_search), fonts,
            { query, done -> client?.run({ this.search(query) }, done) },
            { showArt(it) },
            ::openTitle)
        sheet = RefineSheet(findViewById(R.id.home_refine), fonts, ::onRefine) { header.filter.requestFocus() }

        playButton.setOnClickListener { heroItem?.let { startPlay(it, null, null, playButton) } }
        findViewById<View>(R.id.home_details).setOnClickListener { heroItem?.let(::openTitle) }

        rows = RowsAdapter(fonts)
        rowsView.layoutManager = TopAlignedLayoutManager(this)
        rowsView.adapter = rows
        rowsView.itemAnimator = null
        val gap = Mercury.dp(body, RowsAdapter.GAP_DP)
        val gridCard = (resources.displayMetrics.widthPixels - 2 * Mercury.dp(body, Mercury.SAFE_X) - (GRID_COLUMNS - 1) * gap) / GRID_COLUMNS
        grid = cards(gridCard) { if (!loading && !exhausted && !TABS[tabIndex].let { it.kind == Kind.LIBRARY }) load(reset = false) }
        gridView.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        gridView.adapter = grid
        gridView.itemAnimator = null
        val side = Mercury.dp(body, Mercury.SAFE_X) - gap / 2
        gridView.setPadding(side, gap / 2, side, gridView.paddingBottom)
        // A focused row's name brightens; the others rest.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> paintRowLabels() }

        refine = Refine.decode(homePrefs.getString(KEY_REFINE, null))
        tabIndex = homePrefs.getInt(KEY_TAB, 0).coerceIn(TABS.indices)
        catalogIndex = homePrefs.getInt(KEY_CATALOG, 0)
        savedInstanceState?.getString(STATE_PLAYING_ID)?.let { id ->
            val season = savedInstanceState.getInt(STATE_PLAYING_SEASON, -1)
            playing = id to (if (season >= 0) NextEpisode(season, savedInstanceState.getInt(STATE_PLAYING_EPISODE), null) else null)
        }
        if (vault.address.isEmpty() || vault.token.isEmpty()) showSetup() else start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        playing?.let { (id, episode) ->
            outState.putString(STATE_PLAYING_ID, id)
            episode?.let {
                outState.putInt(STATE_PLAYING_SEASON, it.season)
                outState.putInt(STATE_PLAYING_EPISODE, it.episode)
            }
        }
    }

    /** The phone app asking to connect while the home screen is up. */
    private val askToConnect: (String, (Boolean) -> Unit) -> Unit = { device, answer -> PairingCard.ask(this, fonts, device, answer) }

    override fun onStart() {
        super.onStart()
        PhoneCompanion.start(this)
        PhoneCompanion.approver = askToConnect
        // The phone code may have changed since the list was built: a phone paired, or the network came up.
        if (panelOpen && panelList === settings) settings.show(settings.focusedIndex())
        if (setup.visibility == View.VISIBLE) pairing.resume()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from another app after a while: Continue may have moved. Back from our own player, its result reloads instead.
        if (playing == null && client != null) {
            HubClient.clearCache()
            refresh()
        }
    }

    override fun onStop() {
        super.onStop()
        if (PhoneCompanion.approver === askToConnect) PhoneCompanion.approver = null
        PhoneCompanion.stop()
        pairing.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        header.stop()
    }

    // ---- The hub --------------------------------------------------------------------------------

    private fun start() {
        // A late 401 from a client already replaced must not wipe the token just paired.
        client = HubClient(vault.address, vault.token).also { hub -> hub.onUnauthorized = { if (client === hub) pairAgain() } }
        showSetup(false)
        all = emptyList()
        selectTab(tabIndex)
        client?.run({ presets() }) { result -> result.getOrNull()?.let { Quality.caps = it } }
        client?.run({ healthy() }) { result -> header.setAttention(result.getOrNull() != true) }
    }

    /**
     * The first-run screen covers Home; what's under it is taken out of focus so the d-pad can't wander
     * there. It goes straight to a code for the hub already known, unless [rediscover] asks to look again.
     */
    private fun showSetup(show: Boolean = true, rediscover: Boolean = false, note: Int? = null) {
        if (show) {
            if (panelOpen) closePanel()
            if (sheet.isOpen) sheet.close()
            if (titlePage.isOpen) closeTitle()
            if (search.isOpen) closeSearch(focusSearch = false)
        }
        listOf(body, headerRoot).forEach { (it as ViewGroup).descendantFocusability =
            if (show) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS }
        if (!show) return pairing.hide()
        val known = vault.address.takeIf { it.isNotEmpty() && !rediscover }?.let { FoundHub(it.substringAfter("://"), it) }
        pairing.show(known, note)
    }

    /** The hub refused the token: forget it and ask for the code again. Nothing retries on its own, so this can't loop. */
    private fun pairAgain() {
        if (setup.visibility == View.VISIBLE) return
        vault.token = ""
        client = null
        showSetup(note = R.string.setup_repair)
    }

    /** The catalogs, fetched once; Home's later rows and Browse's sub-tabs both come from them. */
    private fun withCatalogs(then: () -> Unit) {
        if (catalogs.isNotEmpty()) return then()
        val hub = client ?: return
        hub.run({ this.catalogs() }) { result ->
            result.onSuccess {
                catalogs = it
                catalogIndex = catalogIndex.coerceIn(0, (it.size - 1).coerceAtLeast(0))
                buildCatalogTabs()
            }.onFailure { header.setAttention(true) }
            then()
        }
    }

    private fun unreachable(error: Throwable) {
        message.text = getString(R.string.home_unreachable, vault.address.substringAfter("://"), error.message ?: error.javaClass.simpleName)
    }

    /** Continue and Next Up (and the grid on screen) again, after a film or a while away. */
    private fun refresh() {
        if (home) slots.filter { it.refreshes }.forEach { fillSlot(it) } else load(reset = true)
    }

    // ---- Tabs -----------------------------------------------------------------------------------

    private fun selectTab(index: Int) {
        if (search.isOpen) closeSearch(focusSearch = false)
        val changed = index != tabIndex || (if (TABS[index].kind == Kind.HOME) slots.isEmpty() else all.isEmpty())
        tabIndex = index
        homePrefs.edit { putInt(KEY_TAB, index) }
        header.setActive(index)
        header.setFilter(refine.count, TABS[index].refinable)
        hero.visibility = if (home) View.VISIBLE else View.GONE
        rowsView.visibility = if (home) View.VISIBLE else View.GONE
        gridBar.visibility = if (home) View.GONE else View.VISIBLE
        gridView.visibility = if (home) View.GONE else View.VISIBLE
        catalogsScroll.visibility = if (TABS[index].kind == Kind.BROWSE) View.VISIBLE else View.GONE
        fade.background = if (home) Mercury.backdropFade(this) else Mercury.bandFade(this, BAND_DP)
        settleArt()
        paintWhisper()
        if (!changed) return
        if (home) loadHome() else {
            lastGridCard = null
            all = emptyList()
            shown = emptyList()
            grid.submit(emptyList())
            load(reset = true)
        }
    }

    private fun buildCatalogTabs() {
        catalogsRow.removeAllViews()
        catalogs.forEachIndexed { index, catalog ->
            val view = Mercury.text(this, fonts.mercuryMono, 11f, tracking = 0.2f).apply {
                text = catalogName(catalog).uppercase()
                setPadding(0, Mercury.dp(this, 4f), 0, Mercury.dp(this, 4f))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, _ -> paintCatalogs() }
                setOnClickListener {
                    catalogIndex = index
                    homePrefs.edit { putInt(KEY_CATALOG, index) }
                    paintCatalogs()
                    lastGridCard = null
                    load(reset = true)
                }
            }
            catalogsRow.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Mercury.dp(body, 24f)
            })
        }
        paintCatalogs()
    }

    /** Cinemeta names its film and show lists alike ("Popular"); the type tells them apart. */
    private fun catalogName(catalog: Catalog): String {
        val twin = catalogs.count { it.name == catalog.name } > 1
        return if (twin) getString(if (catalog.type == "series") R.string.home_catalog_shows else R.string.home_catalog_movies, catalog.name) else catalog.name
    }

    /** Browse's catalogs: a quiet row, the chosen one in the accent. */
    private fun paintCatalogs() {
        for (i in 0 until catalogsRow.childCount) {
            val view = catalogsRow.getChildAt(i) as TextView
            val active = i == catalogIndex
            view.alpha = if (active) 1f else if (view.isFocused) CATALOG_FOCUSED else CATALOG_REST
            view.setTextColor(Mercury.color(this, if (active) R.color.mercury_blue else R.color.mercury_fg))
        }
    }

    /** What the filters are doing, in one mono line: "ACTION · 1–2 H · 12 TITLES". */
    private fun paintWhisper() {
        val on = TABS[tabIndex].refinable && refine.count > 0
        whisper.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val choices = refine.genres + refine.lengths.map { Refine.LENGTHS[it].label } + refine.formats.map { Refine.FORMATS[it].label } +
            listOfNotNull(Refine.SORTS.getOrNull(refine.sort)?.label)
        val count = resources.getQuantityString(R.plurals.refine_count, shown.size, shown.size)
        whisper.text = getString(R.string.home_whisper, choices.joinToString(" · ").uppercase(), count)
    }

    // ---- Home: hero and rows --------------------------------------------------------------------

    private fun loadHome() {
        val hub = client ?: return
        message.setText(R.string.home_loading)
        heroItem = null
        lastRowCard = null
        homeFocusPlaced = false
        rows.rows.clear()
        rows.notifyDataSetChanged()
        hub.run({ healthy() }) { result -> header.setAttention(result.getOrNull() != true) }
        hub.run({ shelves() }) { result ->
            val list = result.getOrNull()
            // A hub from before shelves (or one that can't say): the built-in rows still work.
            if (list == null) return@run builtInHome()
            shelves = list
            slots = list.filter { !it.hidden }.map { shelf ->
                val refreshes = shelf.kind == "builtin" && (shelf.ref == "continue" || shelf.ref == "next")
                Slot(shelf.title, paged = true, shelf = shelf, refreshes = refreshes) { skip -> shelf(shelf.id, skip) }
            }
            slots.forEach(::fillSlot)
            if (slots.isEmpty()) message.setText(R.string.home_empty)
        }
    }

    /** Continue, Next Up, New, then each catalog: Home's rows when the hub doesn't keep its own. */
    private fun builtInHome() {
        val fixed = listOf(
            Slot(getString(R.string.home_tab_continue), paged = false, refreshes = true) { library("continue").sortedByDescending { it.resume?.updatedAt ?: 0L } },
            Slot(getString(R.string.home_tab_next), paged = false, refreshes = true) { library("next") },
            Slot(getString(R.string.home_tab_new), paged = true) { skip -> newReleases(skip) },
        )
        slots = fixed
        fixed.forEach(::fillSlot)
        withCatalogs {
            // Home was reloaded meanwhile: that load adds its own catalog rows.
            if (slots !== fixed) return@withCatalogs
            val more = catalogs.map { catalog -> Slot(catalogName(catalog), paged = true) { skip -> catalog(catalog, skip) } }
            slots = fixed + more
            more.forEach(::fillSlot)
        }
    }

    // ---- Home's rows, as the hub keeps them: move, hide, rename (long-press OK on a row) ---------

    /** The hub's whole row list, hidden ones included, as last read; saved back whole. */
    private var shelves: List<Shelf> = emptyList()

    private fun rowMenu(slot: Slot) {
        val shelf = slot.shelf ?: return
        val visible = shelves.filter { !it.hidden }
        val at = visible.indexOfFirst { it.id == shelf.id }
        val options = mutableListOf<OptionList.RowSpec>()
        fun item(label: Int, hint: Int?, action: () -> Unit) {
            options += OptionList.RowSpec.Item { Option(getString(label), hint = hint?.let(::getString)) { closePanel(); action() } }
        }
        if (at > 0) item(R.string.row_move_up, R.string.row_shared_hint) { saveShelves(swap(shelf, visible[at - 1])) }
        if (at in 0 until visible.size - 1) item(R.string.row_move_down, R.string.row_shared_hint) { saveShelves(swap(shelf, visible[at + 1])) }
        item(R.string.row_hide, R.string.row_hide_hint) { saveShelves(shelves.map { if (it.id == shelf.id) it.copy(hidden = true) else it }) }
        item(R.string.row_rename, R.string.row_shared_hint) {
            askName(shelf.title) { name -> saveShelves(shelves.map { if (it.id == shelf.id) it.copy(title = name) else it }) }
        }
        openPanel(OptionList(this, findViewById(R.id.settings_items), findViewById(R.id.settings_hint), fonts) { options },
            getString(R.string.row_kicker), slot.label)
    }

    /** The full list with [a] and [b] trading places (hidden rows between them stay where they are). */
    private fun swap(a: Shelf, b: Shelf): List<Shelf> = shelves.map { if (it.id == a.id) b else if (it.id == b.id) a else it }

    private fun saveShelves(list: List<Shelf>) {
        val hub = client ?: return
        hub.run({ saveShelves(list) }) { result ->
            result.onSuccess { loadHome() }
                .onFailure { Toast.makeText(this, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
        }
    }

    /** A name from the on-screen keyboard (or a phone remote app): a row's, or a new collection's. */
    private fun askName(current: String?, done: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            setText(current)
            setSelection(text.length)
            setSingleLine(true)
            hint = getString(R.string.home_new_collection_hint)
        }
        android.app.AlertDialog.Builder(this)
            .setView(android.widget.FrameLayout(this).apply {
                val pad = Mercury.dp(body, 24f)
                setPadding(pad, pad, pad, 0)
                addView(field)
            })
            .setPositiveButton(R.string.row_ok) { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton(R.string.row_cancel, null)
            .show()
        field.requestFocus()
    }

    /** Loads [slot]'s first page; the row appears (in its place among the others) once it has titles, and goes if it has none. */
    private fun fillSlot(slot: Slot) {
        val hub = client ?: return
        slot.loading = true
        hub.run({ slot.fetch(this, 0) }) done@{ result ->
            slot.loading = false
            if (slot !in slots) return@done
            // One row that can't load just isn't there; the screen only says so when nothing loaded at all.
            val items = result.getOrElse { error ->
                if (rows.rows.isEmpty() && slots.none { it.loading }) unreachable(error)
                return@done
            }
            val existing = slot.row
            when {
                items.isEmpty() && existing != null -> {
                    val index = rows.rows.indexOf(existing)
                    rows.rows.removeAt(index)
                    rows.notifyItemRemoved(index)
                    slot.row = null
                }
                items.isEmpty() -> Unit
                existing != null -> existing.cards.submit(items)
                else -> {
                    lateinit var cards: CardAdapter
                    cards = cards(Mercury.dp(body, ROW_CARD_DP)) { if (slot.paged) more(slot, cards) }
                    cards.submit(items)
                    val row = ShelfRow(slot.label, cards, null)
                    slot.row = row
                    val index = slots.takeWhile { it !== slot }.count { it.row != null }
                    rows.rows.add(index, row)
                    rows.notifyItemInserted(index)
                    if (index == 0) rowsView.scrollToPosition(0)
                }
            }
            message.text = if (rows.rows.isEmpty() && slots.none { it.loading }) getString(R.string.home_empty) else null
            // Until a poster has been chosen, the hero (and focus) belong to the first title of the first row, whichever row loads first.
            if (lastRowCard == null) rows.rows.firstOrNull()?.let { first ->
                first.cards.items.firstOrNull()?.let { showHero(it, first.label) }
                if (!homeFocusPlaced && !overlayOpen() && !header.contains(currentFocus)) {
                    homeFocusPlaced = true
                    rowsView.post { firstRowCard()?.requestFocus() }
                }
            }
        }
    }

    /** The next page of a paged row, appended in place. */
    private fun more(slot: Slot, cards: CardAdapter) {
        val hub = client ?: return
        if (slot.loading || slot.exhausted) return
        slot.loading = true
        val skip = cards.items.size
        hub.run({ slot.fetch(this, skip) }) { result ->
            slot.loading = false
            val known = cards.items.map { it.id }.toSet()
            val fresh = result.getOrNull().orEmpty().filter { it.id !in known }
            if (fresh.isEmpty()) slot.exhausted = true else cards.append(fresh)
        }
    }

    /** Cards that follow focus for the hero and backdrop, fill in missing art, and ask for more near their end. */
    private fun cards(widthPx: Int, nearEnd: () -> Unit): CardAdapter {
        lateinit var adapter: CardAdapter
        adapter = CardAdapter(fonts, widthPx, Mercury.dp(body, RowsAdapter.GAP_DP), { item, _ -> onCardFocus(adapter, item) }, ::openTitle, nearEnd)
        adapter.onBound = { item ->
            client?.let { hub ->
                // Posted: the record may already be cached, and an adapter can't change while it binds.
                HomeArt.complete(hub, item) { fresh -> if (fresh != item) main.post { adapter.replace(fresh) } }
            }
        }
        return adapter
    }

    private fun onCardFocus(adapter: CardAdapter, item: HubItem) {
        val focused = currentFocus
        showArt(item)
        if (focused != null && isInside(focused, rowsView)) {
            lastRowCard = focused
            showHero(item, rows.rows.firstOrNull { it.cards === adapter }?.label)
        } else if (focused != null && isInside(focused, gridView)) {
            lastGridCard = focused
        }
    }

    /** The hero: logo or title, the credits in mono, two lines of overview, and Resume or Play. */
    private fun showHero(item: HubItem, kicker: String?) {
        heroItem = item
        showArt(item)
        findViewById<TextView>(R.id.home_hero_kicker).text = kicker?.uppercase()
        val logo = findViewById<ImageView>(R.id.home_hero_logo)
        val title = findViewById<TextView>(R.id.home_hero_title)
        title.text = item.title
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        HomeArt.into(logo, HomeArt.logo(item), Mercury.dp(logo, 340f)) {
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
        }
        findViewById<TextView>(R.id.home_hero_credits).text = Mercury.credits(item, showLibrary = false)
        findViewById<TextView>(R.id.home_hero_overview).text = item.overview
        if (!pending) playButton.setText(if (Mercury.progress(item) != null) R.string.home_resume else R.string.home_play)
        // A show's seasons come from its full record; fetched once, the credits line fills in.
        client?.let { hub -> HomeArt.fill(hub, item) { fresh -> if (heroItem?.id == fresh.id) showHero(fresh, kicker) } }
    }

    private fun paintRowLabels() {
        for (i in 0 until rowsView.childCount) {
            val row = rowsView.getChildAt(i) as? LinearLayout ?: continue
            row.getChildAt(0).alpha = if (row.hasFocus()) 1f else Mercury.TAB_REST
        }
    }

    private fun firstRowCard(): View? = (rowsView.findViewHolderForAdapterPosition(0) as? RowsAdapter.Holder)?.strip?.getChildAt(0)

    /** Back down into the rows: the poster last chosen if it's still on screen, else the first one showing. */
    private fun focusRows() {
        val shown = (rowsView.getChildAt(0)?.let { rowsView.getChildViewHolder(it) } as? RowsAdapter.Holder)?.strip?.getChildAt(0)
        (lastRowCard?.takeIf { it.isAttachedToWindow && isInside(it, rowsView) } ?: shown)?.requestFocus()
    }

    private fun focusGrid() {
        (lastGridCard?.takeIf { it.isAttachedToWindow && isInside(it, gridView) } ?: gridView.getChildAt(0))?.requestFocus()
            ?: header.focusActive()
    }

    // ---- Every other tab: the grid --------------------------------------------------------------

    /** Loads the current grid tab: from the start, or its next page. */
    private fun load(reset: Boolean) {
        val hub = client ?: return
        val tab = TABS[tabIndex]
        if (tab.kind == Kind.HOME) return
        // Browse needs the catalog list before it can load one.
        if (tab.kind == Kind.BROWSE && catalogs.isEmpty()) return withCatalogs { if (TABS[tabIndex].kind == Kind.BROWSE && catalogs.isNotEmpty()) load(reset) }
        val ticket = ++loadTicket
        if (reset) {
            exhausted = false
            if (grid.itemCount == 0) message.setText(R.string.home_loading)
        }
        val skip = if (reset) 0 else all.size
        val source = catalogs.getOrNull(catalogIndex)
        loading = true
        hub.run({
            when (tab.kind) {
                Kind.LIBRARY -> library(tab.collection!!).let { items ->
                    if (tab.collection == "continue") items.sortedByDescending { it.resume?.updatedAt ?: 0L } else items
                }
                Kind.NEW -> newReleases(skip)
                else -> source?.let { catalog(it, skip) }.orEmpty()
            }
        }) done@{ result ->
            if (ticket != loadTicket) return@done
            loading = false
            result.onSuccess { page ->
                val known = if (reset) emptySet() else all.map { it.id }.toSet()
                val fresh = page.filter { it.id !in known }
                // A library collection comes whole; a page that adds nothing new is the end of a catalog.
                if (tab.kind == Kind.LIBRARY || fresh.isEmpty()) exhausted = true
                all = (if (reset) emptyList() else all) + fresh
                render()
            }.onFailure { error ->
                exhausted = true
                if (reset) {
                    all = emptyList()
                    render()
                }
                unreachable(error)
            }
        }
    }

    private fun render() {
        val filtered = if (TABS[tabIndex].refinable) refine.apply(all) else all
        val showing = shown.map { it.id }
        // A new page under the same filters only adds cards at the end; anything else starts over.
        val appendOnly = shown.isNotEmpty() && filtered.size >= shown.size && filtered.subList(0, shown.size).map { it.id } == showing
        val hadFocus = gridView.hasFocus() || currentFocus == null
        shown = filtered
        if (appendOnly) {
            grid.append(filtered.drop(grid.itemCount))
        } else {
            grid.submit(filtered)
            gridView.scrollToPosition(0)
            lastGridCard = null
            if (hadFocus && !overlayOpen() && filtered.isNotEmpty()) gridView.post { gridView.getChildAt(0)?.requestFocus() }
        }
        filtered.firstOrNull()?.let { if (!gridView.hasFocus()) showArt(it) }
        message.text = when {
            all.isEmpty() && exhausted -> getString(R.string.home_empty)
            shown.isEmpty() && all.isNotEmpty() -> getString(R.string.home_nothing_matches)
            else -> null
        }
        paintWhisper()
        sheet.update(all)
    }

    // ---- Art ------------------------------------------------------------------------------------

    private var pendingArt: HubItem? = null
    private val loadArt = Runnable { pendingArt?.let(::loadArtNow) }

    /** Shows [item]'s backdrop; waits a moment first, so moving past titles doesn't fetch each one. */
    private fun showArt(item: HubItem) {
        pendingArt = item
        main.removeCallbacks(loadArt)
        if (item.id != artId) main.postDelayed(loadArt, ART_DELAY_MS)
    }

    private fun loadArtNow(item: HubItem) {
        artId = item.id
        val url = HomeArt.backdrop(item) ?: return
        HubImages.load(url, resources.displayMetrics.widthPixels) { bitmap ->
            if (bitmap != null && artId == item.id) crossfade(bitmap)
        }
    }

    /**
     * Space: the sky goes under the film's art (which then sits a little back, so the planet's glow
     * shows through) and two layers of stars drift over the fade.
     */
    private fun addSky(fade: View) {
        val root = fade.parent as ViewGroup
        root.setBackgroundColor(Mercury.color(this, R.color.ground))
        val full = { ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(SpaceSky(this, sky = true), 0, full())
        val above = root.indexOfChild(fade) + 1
        root.addView(SpaceSky(this, sky = false), above, full())
        root.addView(StarLayer(this, 160, 0.8f, 1.6f, 30f, 120_000L, 7), above + 1, full())
        root.addView(StarLayer(this, 50, 1.6f, 2.6f, 60f, 90_000L, 11), above + 2, full())
    }

    /** How strongly the film's art shows: set back in Space, and only as a faint band over a grid or under Search. */
    private val artAlpha: Float
        get() = when {
            search.isOpen && !titlePage.isOpen -> SEARCH_ART_ALPHA
            !home && !titlePage.isOpen -> GRID_ART_ALPHA
            Mercury.space -> SPACE_ART_ALPHA
            else -> 1f
        }

    private fun settleArt() {
        (if (frontIsB) artB else artA).takeIf { it.drawable != null }?.animate()?.alpha(artAlpha)
            ?.setDuration(Motion.ENTER_MS)?.setInterpolator(Motion.enter)?.start()
    }

    /** The new art fades in over the old, or the old fades off the new, so there is never a gap to black. */
    private fun crossfade(bitmap: Bitmap) {
        artA.animate().cancel()
        artB.animate().cancel()
        if (frontIsB) {
            artA.setImageBitmap(bitmap)
            artA.alpha = artAlpha
            artB.animate().alpha(0f).setDuration(Mercury.ART_FADE_MS).setInterpolator(Motion.enter).start()
        } else {
            artB.setImageBitmap(bitmap)
            artB.alpha = 0f
            artB.animate().alpha(artAlpha).setDuration(Mercury.ART_FADE_MS).setInterpolator(Motion.enter).start()
        }
        frontIsB = !frontIsB
    }

    // ---- Title page, Search, Refine -------------------------------------------------------------

    private fun openTitle(item: HubItem) {
        if (!titlePage.isOpen) beforeTitle = currentFocus
        showArt(item)
        titlePage.open(item)
        HomeArt.logo(item)?.let { url ->
            HubImages.load(url, Mercury.dp(body, 400f)) { bitmap -> if (bitmap != null && titlePage.item?.id == item.id) titlePage.showLogo(bitmap) }
        }
        hideChrome(true)
        // A title page always shows its art in full, whichever tab it was opened from.
        fade.background = Mercury.backdropFade(this)
        settleArt()
        // The full record (overview, episodes) and the ranked copies are fetched side by side.
        fetchStreams(item)
        if (!item.isSeries) client?.run({ franchise(item) }) { result ->
            result.getOrNull()?.let { series ->
                titlePage.showFranchise(item, series.title, series.parts, ::openTitle) { pinFranchise(series) }
            }
        }
        client?.run({ meta(item) }) done@{ result ->
            val fresh = result.getOrNull() ?: return@done
            if (titlePage.item?.id != item.id) return@done
            titlePage.show(HomeArt.merged(fresh.copy(inLibrary = fresh.inLibrary || item.inLibrary, resume = fresh.resume ?: item.resume,
                nextEpisode = fresh.nextEpisode ?: item.nextEpisode, poster = fresh.poster ?: item.poster, backdrop = fresh.backdrop ?: item.backdrop)))
        }
    }

    private fun streamKey(item: HubItem) = item.id + (HomePlay.episodeFor(item)?.let { ":${it.season}:${it.episode}" } ?: "")

    private fun cachedStreams(item: HubItem): List<HubStream>? =
        streamCache[streamKey(item)]?.takeIf { System.currentTimeMillis() - it.first < STREAMS_TTL_MS }?.second

    /** The hub's ranked copies for [item]: the best one's badges go on the title page, the rest wait for Other versions. */
    private fun fetchStreams(item: HubItem) {
        val hub = client ?: return
        val key = streamKey(item)
        cachedStreams(item)?.let { list ->
            titlePage.showStream(list.firstOrNull())
            return
        }
        if (!streamsAsked.add(key)) return
        hub.run({ streams(item, HomePlay.episodeFor(item)) }) { result ->
            streamsAsked.remove(key)
            val list = result.getOrNull()
            if (list != null) streamCache[key] = System.currentTimeMillis() to list
            if (titlePage.item?.id == item.id) titlePage.showStream(list?.firstOrNull())
            if (versions.isOpen && titlePage.item?.id == item.id) versions.show(list.orEmpty(), result.exceptionOrNull()?.message)
        }
    }

    private fun closeTitle() {
        if (versions.isOpen) versions.close()
        titlePage.close()
        hideChrome(false)
        fade.background = if (home) Mercury.backdropFade(this) else Mercury.bandFade(this, BAND_DP)
        settleArt()
        (if (home) heroItem else null)?.let(::showArt)
        beforeTitle?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }?.requestFocus() ?: header.focusActive()
        beforeTitle = null
    }

    /** Fades the header, Home and Search away behind the title page, and keeps focus out of them. */
    private fun hideChrome(hide: Boolean) {
        listOf(body, headerRoot, findViewById(R.id.home_search)).forEach { view ->
            (view as ViewGroup).descendantFocusability = if (hide) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            view.animate().alpha(if (hide) 0f else 1f).setDuration(if (hide) Motion.EXIT_MS else Motion.ENTER_MS)
                .setInterpolator(if (hide) Motion.exit else Motion.enter).start()
        }
    }

    private fun openSearch() {
        if (client == null) return
        search.open()
        header.searchOpen = true
        header.setFilter(refine.count, shown = false)
        settleArt()
        body.visibility = View.INVISIBLE
    }

    private fun closeSearch(focusSearch: Boolean) {
        search.close()
        header.searchOpen = false
        header.setFilter(refine.count, TABS[tabIndex].refinable)
        settleArt()
        body.visibility = View.VISIBLE
        body.alpha = 0f
        body.animate().alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        if (focusSearch) header.search.requestFocus()
    }

    private fun openRefine() {
        if (client == null || !TABS[tabIndex].refinable) return
        if (search.isOpen) closeSearch(focusSearch = false)
        sheet.open(refine, all, (if (frontIsB) artB else artA).drawable)
    }

    private fun onRefine(next: Refine) {
        refine = next
        homePrefs.edit { putString(KEY_REFINE, next.encode()) }
        render()
        header.setFilter(refine.count, TABS[tabIndex].refinable)
    }

    private fun overlayOpen() = panelOpen || sheet.isOpen || titlePage.isOpen || search.isOpen || versions.isOpen || setup.visibility == View.VISIBLE

    // ---- Play, Other versions, Add to library (a show is added whole) ---------------------------

    /**
     * Asks the hub for the best stream now (or plays [stream]) and hands it to the player the way any
     * app would. [episode] picks one of a show's episodes; null means the next one. The press is
     * felt at once: [pressed] turns to "Starting…", the screen dims and input is held until the
     * player opens or the hub says no.
     */
    private fun startPlay(item: HubItem, stream: HubStream?, episode: NextEpisode? = null, pressed: TextView? = null) {
        val hub = client ?: return
        if (pending) return
        beginPending(pressed)
        val episode = episode ?: HomePlay.episodeFor(item)
        val choice = prefs.quality
        // The link test takes a few seconds the first time; say so rather than sit silent.
        if (stream == null && choice == Quality.AUTO && Quality.measuredMbps() == null) Toast.makeText(this, R.string.quality_measuring, Toast.LENGTH_SHORT).show()
        hub.run({
            val info = if (stream != null) {
                PlayInfo(stream.url, stream.headers, stream.badges)
            } else {
                // Auto measures the link once per half hour (or after a stall); a failed probe falls back to Standard.
                val mbps = if (choice != Quality.AUTO) null else Quality.measuredMbps()
                    ?: runCatching { probe(item, episode) }.getOrNull()?.also(Quality::remember)
                this.play(item, episode, Quality.query(choice, mbps))
            }
            // A missing or unreachable shared position just means the box's own memory decides.
            info to runCatching { this.resume(item, episode) }.getOrNull()
        }) { result ->
            result.onSuccess { (info, shared) ->
                val local = ResumeStore(this).find(listOf(HomePlay.localKey(item, episode, info.badges.cut)))
                playing = item.id to episode
                startActivityForResult(HomePlay.intent(this, item, episode, info, HomePlay.startFrom(shared, local?.savedAt)), REQUEST_PLAY)
            }.onFailure { error ->
                endPending()
                Toast.makeText(this, getString(R.string.home_cant_play, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun beginPending(pressed: TextView?) {
        pending = true
        dim.animate().cancel()
        dim.visibility = View.VISIBLE
        dim.animate().alpha(1f).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.enter).start()
        pressed ?: return
        pendingButton = pressed
        pendingLabel = pressed.text
        pressed.setText(R.string.home_starting)
        val spinner = SpinnerDrawable(pressed.currentTextColor, Mercury.dp(pressed, 14f), Mercury.dp(pressed, 2f).toFloat())
        pressed.setCompoundDrawablesRelativeWithIntrinsicBounds(spinner, null, null, null)
        pressed.compoundDrawablePadding = Mercury.dp(pressed, 8f)
        spinner.start()
    }

    private fun endPending() {
        if (!pending) return
        pending = false
        dim.animate().cancel()
        dim.animate().alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit).withEndAction { dim.visibility = View.GONE }.start()
        pendingButton?.let { button ->
            (button.compoundDrawablesRelative[0] as? SpinnerDrawable)?.stop()
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            button.text = pendingLabel
        }
        pendingButton = null
        pendingLabel = null
    }

    override fun onPause() {
        super.onPause()
        // The player has taken over: what's left behind is the screen as it was.
        endPending()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PLAY) return
        val (id, episode) = playing ?: return
        playing = null
        val hub = client ?: return
        val position = data?.getLongExtra(IntentContract.EXTRA_VLC_POSITION, -1L) ?: -1L
        val duration = data?.getLongExtra(IntentContract.EXTRA_VLC_DURATION, 0L) ?: 0L
        if (resultCode != RESULT_OK || position < 0) return
        // Whether or not the hub took it, Continue is reloaded so it shows where things stand.
        hub.run({ saveResume(id, episode, position, duration) }) {
            refresh()
            // The episode list's progress marks come from the resume memory just written.
            titlePage.item?.let { titlePage.show(it) }
        }
    }

    /** Opens at once: the copies fetched when the page opened, or skeleton rows until they come. */
    private fun showVersions(item: HubItem) {
        beforeVersions = currentFocus
        findViewById<ViewGroup>(R.id.home_title_page).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        versions.open(item.title, cachedStreams(item), (if (frontIsB) artB else artA).drawable)
        if (cachedStreams(item) == null) fetchStreams(item)
    }

    /** Add to collection: the hub's hand-made collections, and a new one named on the spot. */
    private fun addToCollection(item: HubItem) {
        val hub = client ?: return
        hub.run({ manualCollections() }) { result ->
            val list = result.getOrElse {
                Toast.makeText(this, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                return@run
            }
            fun add(collection: HubCollection) = hub.run({ addToCollection(collection, item) }) { added ->
                Toast.makeText(this, added.exceptionOrNull()?.message ?: getString(R.string.home_added_to, collection.title), Toast.LENGTH_SHORT).show()
            }
            val options = list.map { collection ->
                OptionList.RowSpec.Item { Option(collection.title) { closePanel(); add(collection) } }
            } + OptionList.RowSpec.Item {
                Option(getString(R.string.home_new_collection)) {
                    closePanel()
                    askName(null) { name ->
                        hub.run({ createCollection(name) }) { created ->
                            created.onSuccess(::add).onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
            openPanel(OptionList(this, findViewById(R.id.settings_items), findViewById(R.id.settings_hint), fonts) { options },
                getString(R.string.home_collect_kicker), item.title)
        }
    }

    /** Pin to Home: the film series becomes a row at the end of Home (on every box). */
    private fun pinFranchise(series: Franchise) {
        val hub = client ?: return
        hub.run({
            val current = shelves()
            saveShelves(current + Shelf("", "franchise", series.collectionId, series.title, false))
        }) { result ->
            result.onSuccess {
                Toast.makeText(this, R.string.home_pinned, Toast.LENGTH_SHORT).show()
                slots = emptyList()
            }.onFailure { Toast.makeText(this, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
        }
    }

    private fun keep(item: HubItem) {
        val hub = client ?: return
        hub.run({ this.keep(item) }) { result ->
            result.onSuccess { kept ->
                all = all.map { if (it.id == kept.id) it.copy(inLibrary = true) else it }
                shown = shown.map { if (it.id == kept.id) it.copy(inLibrary = true) else it }
                (rows.rows.map { it.cards } + grid).forEach { cards ->
                    cards.items.firstOrNull { it.id == kept.id }?.let { cards.replace(it.copy(inLibrary = true)) }
                }
                heroItem?.takeIf { it.id == kept.id }?.let { showHero(it.copy(inLibrary = true), findViewById<TextView>(R.id.home_hero_kicker).text?.toString()) }
                titlePage.item?.takeIf { it.id == kept.id }?.let { titlePage.show(it.copy(inLibrary = true)) }
                Toast.makeText(this, R.string.home_kept, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, getString(R.string.home_cant_keep, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- Side panel: settings -------------------------------------------------------------------

    private fun openSettings() = openPanel(settings, getString(R.string.settings_kicker), getString(R.string.settings_title))

    /** The side panel with one of the app's one-line menus: settings, a row's menu, the collections. */
    private fun openPanel(options: OptionList, kicker: String, title: String) {
        findViewById<TextView>(R.id.settings_kicker).text = kicker
        findViewById<TextView>(R.id.settings_title).text = title
        if (!panelOpen) beforePanel = currentFocus
        panelOpen = true
        panelList = options
        findViewById<View>(R.id.panel_scrim).visibility = View.VISIBLE
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.translationX = panel.width * PANEL_SLIDE
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        options.show()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        findViewById<View>(R.id.panel_scrim).visibility = View.GONE
        // Out the way it came in, a touch quicker, rather than vanishing.
        panel.animate().cancel()
        panel.animate().translationX(panel.width * PANEL_SLIDE).alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit)
            .withEndAction { panel.visibility = View.INVISIBLE }.start()
        beforePanel?.takeIf { it.isShown }?.requestFocus()
        // The theme is chosen in Settings; it takes hold as the panel closes.
        if (Mercury.space != (prefs.homeTheme == PlayerPrefs.THEME_SPACE)) recreate()
    }

    // ---- Remote ---------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A play on its way: nothing moves until the player opens or the hub answers no.
        if (pending) return true
        if (rowLongPress(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && !panelOpen && !sheet.isOpen && !titlePage.isOpen && setup.visibility != View.VISIBLE) {
            if (search.isOpen && typeIntoSearch(event)) return true
            if (!search.isOpen && moveFocus(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var longPressed = false

    /**
     * Long-press OK on a poster in one of Home's rows opens that row's menu (Move, Hide, Rename). The
     * rest of that press, its release included, is swallowed so it doesn't also open the title.
     */
    private fun rowLongPress(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER) return false
        if (longPressed) {
            if (event.action == KeyEvent.ACTION_UP) longPressed = false
            return true
        }
        val card = currentFocus as? PosterCard ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 1 || !isInside(card, rowsView)) return false
        val row = rows.rows.getOrNull(rowOf(card)) ?: return false
        val slot = slots.firstOrNull { it.row === row }?.takeIf { it.shelf != null } ?: return false
        longPressed = true
        card.isPressed = false
        rowMenu(slot)
        return true
    }

    /** Letters from a keyboard or a phone remote app go straight into Search. */
    private fun typeIntoSearch(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DEL) {
            search.type(null)
            return true
        }
        val char = event.unicodeChar.toChar()
        if (event.unicodeChar == 0 || !(char.isLetterOrDigit() || char == ' ')) return false
        search.type(char)
        return true
    }

    /**
     * The moves positions alone get wrong: header → hero → rows on Home (and back up the same way),
     * header → catalogs → grid elsewhere, and rows that stop at their ends instead of wrapping.
     */
    private fun moveFocus(keyCode: Int): Boolean {
        val focused = currentFocus ?: return false
        val inHeader = header.contains(focused)
        val inHero = focused.parent === heroButtons
        val inCatalogs = focused.parent === catalogsRow
        val rowCard = focused is PosterCard && isInside(focused, rowsView)
        val gridCard = focused is PosterCard && isInside(focused, gridView)
        val catalogTab = catalogsRow.getChildAt(catalogIndex)?.takeIf { catalogsScroll.visibility == View.VISIBLE }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                inHeader && home -> playButton.requestFocus()
                inHeader -> catalogTab?.requestFocus() ?: focusGrid()
                inHero -> focusRows()
                inCatalogs -> focusGrid()
                else -> return false
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                inHeader -> Unit
                inHero || inCatalogs -> header.focusActive()
                rowCard && rowOf(focused) == 0 -> playButton.requestFocus()
                // The row above may have scrolled off under the hero; bring it back rather than jump to the hero.
                rowCard -> focusRow(rowOf(focused) - 1, focused)
                gridCard && gridView.getChildAdapterPosition(focused) < GRID_COLUMNS -> catalogTab?.requestFocus() ?: header.focusActive()
                else -> return false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                rowCard -> if (positionInStrip(focused) == 0) Unit else return false
                gridCard -> if (gridView.getChildAdapterPosition(focused) % GRID_COLUMNS == 0) Unit else return false
                else -> return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                rowCard -> {
                    val strip = focused.parent as RecyclerView
                    if (positionInStrip(focused) == (strip.adapter?.itemCount ?: 0) - 1) Unit else return false
                }
                gridCard -> {
                    val position = gridView.getChildAdapterPosition(focused)
                    if (position % GRID_COLUMNS == GRID_COLUMNS - 1 || position == grid.itemCount - 1) Unit else return false
                }
                else -> return false
            }
            else -> return false
        }
        return true
    }

    /** Focuses the poster in row [index] nearest (across) to [from], scrolling the row into place first if need be. */
    private fun focusRow(index: Int, from: View) {
        val x = IntArray(2).also { from.getLocationOnScreen(it) }[0]
        val pick = {
            val strip = (rowsView.findViewHolderForAdapterPosition(index) as? RowsAdapter.Holder)?.strip
            (0 until (strip?.childCount ?: 0)).map { strip!!.getChildAt(it) }
                .minByOrNull { child -> kotlin.math.abs(IntArray(2).also { child.getLocationOnScreen(it) }[0] - x) }
        }
        pick()?.requestFocus() ?: run {
            rowsView.scrollToPosition(index)
            rowsView.post { pick()?.requestFocus() }
        }
    }

    private fun positionInStrip(card: View) = (card.parent as RecyclerView).getChildAdapterPosition(card)

    /** Which of Home's rows [card] is in. */
    private fun rowOf(card: View): Int {
        var view: View = card
        while (view.parent !== rowsView) view = view.parent as? View ?: return -1
        return rowsView.getChildAdapterPosition(view)
    }

    private fun isInside(view: View, parent: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (panelOpen) {
            when (keyCode) {
                // Left and Right step the focused setting; they never leave the panel.
                KeyEvent.KEYCODE_DPAD_LEFT -> { panelList?.step(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { panelList?.step(1); return true }
                KeyEvent.KEYCODE_MENU -> { closePanel(); return true }
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Still delivered on every Android version this app supports without opting in to predictive back.")
    override fun onBackPressed() {
        val focused = currentFocus
        when {
            panelOpen -> closePanel()
            versions.isOpen -> versions.close()
            sheet.isOpen -> sheet.close()
            titlePage.isOpen -> closeTitle()
            search.isOpen -> if (!search.back()) closeSearch(focusSearch = true)
            setup.visibility == View.VISIBLE && pairing.back() -> Unit
            setup.visibility == View.VISIBLE && client != null -> {
                showSetup(false)
                header.focusActive()
            }
            setup.visibility == View.VISIBLE -> exit()
            // From the posters, Back goes up to the header; from the header, back to Home; from Home's header, out.
            focused == null || !header.contains(focused) -> header.focusActive()
            !home -> {
                selectTab(0)
                header.tabs[0].requestFocus()
            }
            else -> exit()
        }
    }

    private fun exit() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private companion object {
        val TABS = listOf(
            Tab(R.string.home_tab_home, Kind.HOME),
            Tab(R.string.home_tab_continue, Kind.LIBRARY, "continue"),
            Tab(R.string.home_tab_next, Kind.LIBRARY, "next"),
            // New is new releases from the catalogs, paged like Browse (DATAFLOW.md, GET /new).
            Tab(R.string.home_tab_new, Kind.NEW),
            Tab(R.string.home_tab_movies, Kind.LIBRARY, "movies"),
            Tab(R.string.home_tab_shows, Kind.LIBRARY, "shows"),
            Tab(R.string.home_tab_browse, Kind.BROWSE),
        )
        const val GRID_COLUMNS = 6
        /** A row's poster width; 2:3 makes it 150 dp tall. */
        const val ROW_CARD_DP = 100f
        /** How far down a grid's faint band of art reaches. */
        const val BAND_DP = 130f
        const val CATALOG_REST = 0.45f
        const val CATALOG_FOCUSED = 0.85f
        const val ART_DELAY_MS = 140L
        const val PANEL_SLIDE = 0.08f
        const val STREAMS_TTL_MS = 10L * 60 * 1000
        const val SPACE_ART_ALPHA = 0.6f
        const val GRID_ART_ALPHA = 0.24f
        const val SEARCH_ART_ALPHA = 0.4f
        const val REQUEST_PLAY = 1
        const val KEY_REFINE = "refine"
        const val KEY_TAB = "tab"
        const val KEY_CATALOG = "catalog"
        const val STATE_PLAYING_ID = "playing_id"
        const val STATE_PLAYING_SEASON = "playing_season"
        const val STATE_PLAYING_EPISODE = "playing_episode"
    }
}
