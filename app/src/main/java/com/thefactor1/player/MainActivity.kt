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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.home.HomeArt
import com.thefactor1.player.home.HomePlay
import com.thefactor1.player.home.Mercury
import com.thefactor1.player.home.PairingSetup
import com.thefactor1.player.home.PosterView
import com.thefactor1.player.home.Refine
import com.thefactor1.player.home.RefineSheet
import com.thefactor1.player.home.SearchScreen
import com.thefactor1.player.home.SpaceSky
import com.thefactor1.player.home.StarLayer
import com.thefactor1.player.home.SpinnerDrawable
import com.thefactor1.player.home.TitlePage
import com.thefactor1.player.home.VersionsSheet
import com.thefactor1.player.hub.Catalog
import com.thefactor1.player.hub.FoundHub
import com.thefactor1.player.hub.HubClient
import com.thefactor1.player.hub.HubImages
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.hub.HubResume
import com.thefactor1.player.hub.HubStream
import com.thefactor1.player.hub.HubVault
import com.thefactor1.player.hub.NextEpisode
import com.thefactor1.player.hub.PlayInfo
import com.thefactor1.player.hub.Quality
import com.thefactor1.player.launch.IntentContract
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.resume.ResumeStore
import com.thefactor1.player.ui.DefaultSettings
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.Motion
import com.thefactor1.player.ui.OptionList
import com.thefactor1.player.ui.PairingCard
import com.thefactor1.player.ui.ThisTv

/**
 * Home, the Programme layout over the hub: tabs for the collections, the list on the left, the
 * focused title's backdrop on the right, and Play and Details. Details, Search and Refine open over
 * it; the settings for every video stay in the side panel. Without a hub it asks for one.
 */
class MainActivity : Activity() {

    /** A tab: a library collection read whole, New (paged), or Browse (a paged catalog of the user's choosing). */
    private class Tab(val label: Int, val collection: String?, val isNew: Boolean = false) {
        val isBrowse: Boolean get() = collection == null && !isNew
        /** Continue and Next Up are where you left things; filtering them would hide what you're in the middle of. */
        val refinable: Boolean get() = collection != "continue" && collection != "next"
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
    private lateinit var body: View
    private lateinit var message: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var pills: View
    private lateinit var playPill: TextView
    private lateinit var keepPill: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var catalogsScroll: View
    private lateinit var catalogsRow: LinearLayout
    private lateinit var magnifier: ImageView
    private lateinit var filterTab: TextView
    private lateinit var setup: View
    private lateinit var pairing: PairingSetup
    private lateinit var panel: View
    private lateinit var settings: OptionList
    private lateinit var titlePage: TitlePage
    private lateinit var search: SearchScreen
    private lateinit var sheet: RefineSheet
    private lateinit var versions: VersionsSheet
    private lateinit var dim: View

    private var tabIndex = 0
    private var catalogs: List<Catalog> = emptyList()
    private var catalogIndex = 0
    private var refine = Refine()
    /** Everything loaded for the current tab, and the part of it the filters let through. */
    private var all: List<HubItem> = emptyList()
    private var shown: List<HubItem> = emptyList()
    private var selectedId: String? = null
    private var loading = false
    private var exhausted = false
    /** Bumped on every new load, so an answer for a tab already left is dropped. */
    private var loadTicket = 0
    private var artId: String? = null
    private var frontIsB = false
    private var panelList: OptionList? = null
    private var panelOpen = false
    private var beforePanel: View? = null
    private var beforeTitle: View? = null
    /** Each title's ranked copies, fetched when its page opens and kept ten minutes, so Other versions opens at once. */
    private val streamCache = HashMap<String, Pair<Long, List<HubStream>>>()
    private val streamsAsked = HashSet<String>()
    /** A play is on its way to the hub: input is held until the player starts or it fails. */
    private var pending = false
    private var pendingButton: TextView? = null
    private var pendingLabel: CharSequence? = null
    /** What the player is playing for us, so its position goes back to the hub. */
    private var playing: Pair<String, NextEpisode?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PlayerPrefs(this)
        Mercury.space = prefs.homeTheme == PlayerPrefs.THEME_SPACE
        setContentView(R.layout.activity_main)
        fonts = Fonts(this)
        vault = HubVault(this)
        artA = findViewById(R.id.home_art_a)
        artB = findViewById(R.id.home_art_b)
        body = findViewById(R.id.home_body)
        message = findViewById(R.id.home_message)
        scroll = findViewById(R.id.home_scroll)
        list = findViewById(R.id.home_list)
        pills = findViewById(R.id.home_pills)
        playPill = findViewById(R.id.home_play)
        keepPill = findViewById(R.id.home_keep)
        tabsRow = findViewById(R.id.home_tabs)
        catalogsScroll = findViewById(R.id.home_catalogs_scroll)
        catalogsRow = findViewById(R.id.home_catalogs)
        setup = findViewById(R.id.home_setup)
        panel = findViewById(R.id.settings_panel)
        val fade = findViewById<View>(R.id.home_fade)
        fade.background = Mercury.backdropFade(this)
        if (Mercury.space) addSky(fade)
        scroll.layoutParams.width = (resources.displayMetrics.widthPixels * LIST_WIDTH).toInt()

        fonts.apply(fonts.ui, message, playPill, keepPill, findViewById(R.id.home_details), findViewById(R.id.home_hero_overview))
        fonts.apply(fonts.mercuryMono, findViewById(R.id.home_hero_credits))
        fonts.apply(fonts.bold, findViewById(R.id.settings_title))
        fonts.apply(fonts.semibold, findViewById(R.id.settings_kicker))
        fonts.apply(fonts.medium, findViewById(R.id.settings_hint))

        val defaults = DefaultSettings(this, prefs, { facts }) {
            closePanel()
            showSetup(rediscover = true)
        }
        pairing = PairingSetup(findViewById(R.id.home_setup), fonts, { address, token ->
            vault.address = address
            vault.token = token
            HubClient.clearCache()
            catalogs = emptyList()
            catalogsRow.removeAllViews()
            start()
        }, ::openSettings)
        settings = OptionList(this, findViewById(R.id.settings_items), findViewById(R.id.settings_hint), fonts, defaults::rows)
        titlePage = TitlePage(findViewById(R.id.home_title_page), fonts, { item, episode, pressed -> startPlay(item, null, episode, pressed) },
            ::showVersions, ::keep) { item, episode ->
            ResumeStore(this).find(listOf(HomePlay.localKey(item, episode, null)))?.let { HubResume(it.positionMs, it.durationMs, it.savedAt) }
        }
        versions = VersionsSheet(findViewById(R.id.home_versions), fonts, { stream, pressed ->
            val item = titlePage.item ?: return@VersionsSheet
            startPlay(item, stream, null, pressed)
        }) {
            findViewById<ViewGroup>(R.id.home_title_page).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            beforeVersions?.takeIf { it.isAttachedToWindow }?.requestFocus()
        }
        dim = findViewById(R.id.home_dim)
        Mercury.liftRoom(pills as ViewGroup)
        search = SearchScreen(findViewById(R.id.home_search), fonts,
            { query, done -> client?.run({ this.search(query) }, done) },
            { showArt(it) },
            ::openTitle)
        sheet = RefineSheet(findViewById(R.id.home_refine), fonts, ::onRefine) {
            body.animate().translationX(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit).start()
            filterTab.requestFocus()
        }

        playPill.setOnClickListener { selected()?.let { startPlay(it, null, null, playPill) } }
        findViewById<View>(R.id.home_details).setOnClickListener { selected()?.let(::openTitle) }
        keepPill.setOnClickListener { selected()?.let(::keep) }

        refine = Refine.decode(homePrefs.getString(KEY_REFINE, null))
        tabIndex = homePrefs.getInt(KEY_TAB, 0).coerceIn(TABS.indices)
        catalogIndex = homePrefs.getInt(KEY_CATALOG, 0)
        savedInstanceState?.getString(STATE_PLAYING_ID)?.let { id ->
            val season = savedInstanceState.getInt(STATE_PLAYING_SEASON, -1)
            playing = id to (if (season >= 0) NextEpisode(season, savedInstanceState.getInt(STATE_PLAYING_EPISODE), null) else null)
        }
        buildTabs()
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
            load(reset = true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (PhoneCompanion.approver === askToConnect) PhoneCompanion.approver = null
        PhoneCompanion.stop()
        pairing.pause()
    }

    // ---- The hub --------------------------------------------------------------------------------

    private fun start() {
        // A late 401 from a client already replaced must not wipe the token just paired.
        client = HubClient(vault.address, vault.token).also { hub -> hub.onUnauthorized = { if (client === hub) pairAgain() } }
        showSetup(false)
        all = emptyList()
        selectTab(tabIndex)
        client?.run({ presets() }) { result -> result.getOrNull()?.let { Quality.caps = it } }
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
            if (search.isOpen) closeSearch(focusMagnifier = false)
        }
        listOf(body, tabsRow, catalogsScroll).forEach { (it as ViewGroup).descendantFocusability =
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

    /** Loads the current tab: from the start, or its next page. */
    private fun load(reset: Boolean) {
        val hub = client ?: return
        val tab = TABS[tabIndex]
        // Browse needs the catalog list before it can load one.
        if (tab.isBrowse && catalogs.isEmpty()) return loadCatalogs()
        val ticket = ++loadTicket
        if (reset) {
            exhausted = false
            if (list.childCount == 0) message.setText(R.string.home_loading)
        }
        val skip = if (reset) 0 else all.size
        val source = catalogs.getOrNull(catalogIndex)
        loading = true
        hub.run({
            when {
                tab.collection != null -> library(tab.collection)
                tab.isNew -> newReleases(skip)
                source != null -> catalog(source, skip)
                else -> emptyList()
            }
        }) done@{ result ->
            if (ticket != loadTicket) return@done
            loading = false
            result.onSuccess { page ->
                val known = if (reset) emptySet() else all.map { it.id }.toSet()
                val fresh = page.filter { it.id !in known }
                // A library collection comes whole; a page that adds nothing new is the end of a catalog.
                if (tab.collection != null || fresh.isEmpty()) exhausted = true
                all = (if (reset) emptyList() else all) + fresh
                // Continue is "what was I watching": the latest-watched first, whatever order the hub sent.
                if (tab.collection == "continue") all = all.sortedByDescending { it.resume?.updatedAt ?: 0L }
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

    private fun loadCatalogs() {
        val hub = client ?: return
        message.setText(R.string.home_loading)
        hub.run({ this.catalogs() }) done@{ result ->
            result.onSuccess {
                catalogs = it
                catalogIndex = catalogIndex.coerceIn(0, (it.size - 1).coerceAtLeast(0))
                buildCatalogTabs()
            }
            // Left Browse while the list came: the tab on screen has its own load and message.
            if (!TABS[tabIndex].isBrowse) return@done
            result.onSuccess { if (it.isEmpty()) message.setText(R.string.home_empty) else load(reset = true) }.onFailure(::unreachable)
        }
    }

    private fun unreachable(error: Throwable) {
        message.text = getString(R.string.home_unreachable, vault.address.substringAfter("://"), error.message ?: error.javaClass.simpleName)
    }

    // ---- Tabs -----------------------------------------------------------------------------------

    private fun buildTabs() {
        tabsRow.removeAllViews()
        magnifier = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(getColor(R.color.mercury_fg))
            contentDescription = getString(R.string.home_search)
            setPadding(Mercury.dp(this, 4f), Mercury.dp(this, 6f), Mercury.dp(this, 4f), Mercury.dp(this, 9f))
        }
        tabsRow.addView(tabControl(magnifier) { openSearch() }, wrap(end = 26f))
        TABS.forEachIndexed { index, tab ->
            val view = Mercury.text(this, fonts.ui, 18f).apply {
                setText(tab.label)
                setPadding(0, Mercury.dp(this, 4f), 0, Mercury.dp(this, 6f))
            }
            tabsRow.addView(tabControl(view) { selectTab(index) }, wrap(end = 34f))
        }
        tabsRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        filterTab = Mercury.text(this, fonts.mercuryMono, 11f, tracking = 0.18f).apply {
            setPadding(0, Mercury.dp(this, 4f), 0, Mercury.dp(this, 6f))
        }
        tabsRow.addView(tabControl(filterTab) { openRefine() }, wrap(end = 26f))
        val settingsTab = Mercury.text(this, fonts.mercuryMono, 11f, tracking = 0.18f).apply {
            setText(R.string.home_settings_short)
            setPadding(0, Mercury.dp(this, 4f), 0, Mercury.dp(this, 6f))
        }
        tabsRow.addView(tabControl(settingsTab) { openSettings() })
        paintTabs()
    }

    private fun tabControl(view: View, click: () -> Unit): View = view.apply {
        isFocusable = true
        isClickable = true
        setOnClickListener { click() }
        setOnFocusChangeListener { _, _ -> paintTabs() }
    }

    /** The active tab is white with the meniscus under it; the rest sit at 55 % until focused. */
    private fun paintTabs() {
        for (i in 0 until tabsRow.childCount) {
            val view = tabsRow.getChildAt(i)
            val active = when {
                view === magnifier -> search.isOpen
                view === filterTab -> refine.count > 0
                else -> i - 1 == tabIndex && !search.isOpen
            }
            view.alpha = if (active || view.isFocused) 1f else Mercury.TAB_REST
            if (view !== magnifier) view.background = if (active) Mercury.meniscus(this) else null
        }
        filterTab.text = if (refine.count > 0) getString(R.string.home_filter_on, refine.count) else getString(R.string.home_filter)
        filterTab.visibility = if (TABS[tabIndex].refinable && !search.isOpen) View.VISIBLE else View.GONE
        for (i in 0 until catalogsRow.childCount) {
            val view = catalogsRow.getChildAt(i)
            val active = i == catalogIndex
            view.alpha = if (active || view.isFocused) 1f else Mercury.TAB_REST
            view.background = if (active) Mercury.meniscus(this) else null
        }
    }

    private fun buildCatalogTabs() {
        catalogsRow.removeAllViews()
        catalogs.forEachIndexed { index, catalog ->
            // Cinemeta names its film and show lists alike ("Popular"); the type tells them apart.
            val twin = catalogs.count { it.name == catalog.name } > 1
            val view = Mercury.text(this, fonts.ui, 14f).apply {
                text = if (twin) getString(if (catalog.type == "series") R.string.home_catalog_shows else R.string.home_catalog_movies, catalog.name) else catalog.name
                setPadding(0, Mercury.dp(this, 3f), 0, Mercury.dp(this, 5f))
            }
            catalogsRow.addView(tabControl(view) {
                catalogIndex = index
                homePrefs.edit { putInt(KEY_CATALOG, index) }
                paintTabs()
                selectedId = null
                list.removeAllViews()
                load(reset = true)
            }, wrap(end = 24f))
        }
        paintTabs()
    }

    private fun selectTab(index: Int) {
        if (search.isOpen) closeSearch(focusMagnifier = false)
        val changed = index != tabIndex || all.isEmpty()
        tabIndex = index
        homePrefs.edit { putInt(KEY_TAB, index) }
        catalogsScroll.visibility = if (TABS[index].isBrowse) View.VISIBLE else View.GONE
        paintTabs()
        if (changed) {
            selectedId = null
            list.removeAllViews()
            load(reset = true)
        }
    }

    // ---- The list -------------------------------------------------------------------------------

    private fun render() {
        val filtered = if (TABS[tabIndex].refinable) refine.apply(all) else all
        val ids = filtered.map { it.id }
        val showing = shown.map { it.id }
        // A new page under the same filters only adds rows at the end; anything else rebuilds.
        val appendOnly = shown.isNotEmpty() && list.childCount == shown.size && ids.size >= showing.size && ids.subList(0, showing.size) == showing
        val listHadFocus = list.hasFocus() || currentFocus == null
        shown = filtered
        if (appendOnly) {
            filtered.drop(list.childCount).forEach { list.addView(row(it)) }
        } else {
            val keep = selectedId
            list.removeAllViews()
            filtered.forEach { list.addView(row(it)) }
            scroll.scrollTo(0, 0)
            selectedId = null
            val index = shown.indexOfFirst { it.id == keep }.coerceAtLeast(0)
            if (shown.isNotEmpty()) {
                select(index)
                if (listHadFocus && !sheet.isOpen && !titlePage.isOpen && !search.isOpen && !panelOpen) list.getChildAt(index).requestFocus()
            }
        }
        message.text = when {
            all.isEmpty() && exhausted -> getString(R.string.home_empty)
            shown.isEmpty() && all.isNotEmpty() -> getString(R.string.home_nothing_matches)
            else -> null
        }
        pills.visibility = if (shown.isEmpty()) View.INVISIBLE else View.VISIBLE
        sheet.update(all)
    }

    /** A row: the poster (with progress), the name, and the status line under it. */
    private fun row(item: HubItem): View = LinearLayout(this).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        val posterGap = Mercury.dp(this, 16f)
        addView(PosterView(this@MainActivity), LinearLayout.LayoutParams(Mercury.dp(this, POSTER_W_DP), Mercury.dp(this, POSTER_H_DP)).apply {
            marginEnd = posterGap
        })
        val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Mercury.title(this@MainActivity, fonts, ROW_SIZE))
        words.addView(Mercury.text(this@MainActivity, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.14f), wrap(top = 6f))
        addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        alpha = Mercury.ROW_REST
        val gap = Mercury.dp(this, 12f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap }
        setOnFocusChangeListener { v, has -> if (has) select(list.indexOfChild(v)) }
        setOnClickListener { selected()?.let(::openTitle) }
        bindRow(this, item)
        client?.let { hub -> HomeArt.complete(hub, item, ::replaceItem) }
    }

    private fun bindRow(row: View, item: HubItem) {
        val group = row as LinearLayout
        (group.getChildAt(0) as PosterView).bind(item, Mercury.dp(row, POSTER_W_DP * 2))
        val words = group.getChildAt(1) as LinearLayout
        (words.getChildAt(0) as TextView).text = item.title
        val status = words.getChildAt(1) as TextView
        val library = if (TABS[tabIndex].collection == null && item.inLibrary) getString(R.string.home_in_library) else null
        status.text = Mercury.status(item) ?: library ?: item.year?.toString()
    }

    /** A fuller record arrived for a title on screen (art, year): it replaces the old one in place. */
    private fun replaceItem(fresh: HubItem) {
        all = all.map { if (it.id == fresh.id) fresh else it }
        val index = shown.indexOfFirst { it.id == fresh.id }
        if (index < 0) return
        shown = shown.toMutableList().also { it[index] = fresh }
        list.getChildAt(index)?.let { bindRow(it, fresh) }
        if (fresh.id == selectedId) showHero(fresh)
    }

    private fun selected(): HubItem? = shown.firstOrNull { it.id == selectedId }

    /** Makes the row at [index] the large one, shows its art, and fetches more when near the end. */
    private fun select(index: Int) {
        val item = shown.getOrNull(index) ?: return
        if (item.id != selectedId) {
            list.getChildAt(shown.indexOfFirst { it.id == selectedId })?.let { style(it, false) }
            selectedId = item.id
            list.getChildAt(index)?.let { style(it, true) }
            showArt(item)
            showHero(item)
        }
        keepPill.visibility = if (item.inLibrary) View.GONE else View.VISIBLE
        val row = list.getChildAt(index) ?: return
        row.post { scroll.smoothScrollTo(0, (row.top - scroll.height / 4).coerceAtLeast(0)) }
        if (TABS[tabIndex].collection == null && !exhausted && !loading && index >= shown.size - PAGE_AHEAD) load(reset = false)
    }

    private fun style(row: View, on: Boolean) {
        val name = ((row as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(0) as TextView
        name.textSize = Mercury.displaySize(if (on) ROW_SIZE_ON else ROW_SIZE)
        name.maxLines = if (on) 2 else 1
        row.animate().alpha(if (on) 1f else Mercury.ROW_REST).setDuration(Motion.FOCUS_MS).setInterpolator(Motion.enter).start()
    }

    private fun focusList() {
        val index = shown.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        (list.getChildAt(index) ?: tabsRow.getChildAt(tabIndex + 1))?.requestFocus()
    }

    private fun focusActiveTab() {
        tabsRow.getChildAt(if (search.isOpen) 0 else tabIndex + 1)?.requestFocus()
    }

    // ---- Art ------------------------------------------------------------------------------------

    private var pendingArt: HubItem? = null
    private val loadArt = Runnable { pendingArt?.let(::loadArtNow) }

    /** Shows [item]'s backdrop; waits a moment first, so scrolling past titles doesn't fetch each one. */
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

    /** The right-hand block over the backdrop: logo if there is one, the credits in mono, three lines of overview. */
    private fun showHero(item: HubItem) {
        val logo = findViewById<ImageView>(R.id.home_hero_logo)
        logo.visibility = View.GONE
        HomeArt.into(logo, HomeArt.logo(item), Mercury.dp(logo, 360f)) { logo.visibility = View.VISIBLE }
        findViewById<TextView>(R.id.home_hero_credits).text = Mercury.credits(item, showLibrary = TABS[tabIndex].collection == null)
        findViewById<TextView>(R.id.home_hero_overview).text = item.overview
    }

    /**
     * Space: the sky goes under the film's art (which then sits a little back, so the planet's glow
     * shows through) and two layers of stars drift over the fade, where the list is.
     */
    private fun addSky(fade: View) {
        val root = fade.parent as ViewGroup
        root.setBackgroundColor(Mercury.color(this, R.color.ground))
        root.addView(SpaceSky(this, sky = true), 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val above = root.indexOfChild(fade) + 1
        root.addView(SpaceSky(this, sky = false), above, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(StarLayer(this, 160, 0.8f, 1.6f, 30f, 120_000L, 7), above + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(StarLayer(this, 50, 1.6f, 2.6f, 60f, 90_000L, 11), above + 2, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** How strongly the film's art shows: full in Mercury, held back in Space so the sky reads. */
    private val artAlpha get() = (if (Mercury.space) SPACE_ART_ALPHA else 1f) * (if (search.isOpen) SEARCH_ART_ALPHA else 1f)

    /** Search sets the art back so the results read; closing it brings the art forward again. */
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
        // The full record (overview, episodes) and the ranked copies are fetched side by side.
        fetchStreams(item)
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
        if (!search.isOpen) selected()?.let(::showArt)
        beforeTitle?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }?.requestFocus() ?: focusList()
        beforeTitle = null
    }

    /** Fades the tabs, list and Search away behind the title page, and keeps focus out of them. */
    private fun hideChrome(hide: Boolean) {
        listOf(body, tabsRow, catalogsScroll, findViewById(R.id.home_search)).forEach { view ->
            (view as ViewGroup).descendantFocusability = if (hide) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            view.animate().alpha(if (hide) 0f else 1f).setDuration(if (hide) Motion.EXIT_MS else Motion.ENTER_MS)
                .setInterpolator(if (hide) Motion.exit else Motion.enter).start()
        }
    }

    private fun openSearch() {
        if (client == null) return
        search.open()
        settleArt()
        body.visibility = View.INVISIBLE
        catalogsScroll.visibility = View.GONE
        paintTabs()
    }

    private fun closeSearch(focusMagnifier: Boolean) {
        search.close()
        settleArt()
        body.visibility = View.VISIBLE
        body.alpha = 0f
        body.animate().alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        if (TABS[tabIndex].isBrowse) catalogsScroll.visibility = View.VISIBLE
        selected()?.let(::showArt)
        paintTabs()
        if (focusMagnifier) magnifier.requestFocus()
    }

    private fun openRefine() {
        if (client == null || !TABS[tabIndex].refinable) return
        if (search.isOpen) closeSearch(focusMagnifier = false)
        body.animate().translationX(-Mercury.dp(body, Motion.RISE_DP).toFloat()).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        sheet.open(refine, all, (if (frontIsB) artB else artA).drawable)
    }

    private fun onRefine(next: Refine) {
        refine = next
        homePrefs.edit { putString(KEY_REFINE, next.encode()) }
        render()
        paintTabs()
    }

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
            load(reset = true)
            // The episode list's progress marks come from the resume memory just written.
            titlePage.item?.let { titlePage.show(it) }
        }
    }

    private var beforeVersions: View? = null

    /** Opens at once: the copies fetched when the page opened, or skeleton rows until they come. */
    private fun showVersions(item: HubItem) {
        beforeVersions = currentFocus
        findViewById<ViewGroup>(R.id.home_title_page).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        versions.open(item.title, cachedStreams(item), (if (frontIsB) artB else artA).drawable)
        if (cachedStreams(item) == null) fetchStreams(item)
    }

    private fun keep(item: HubItem) {
        val hub = client ?: return
        hub.run({ this.keep(item) }) { result ->
            result.onSuccess { kept ->
                all = all.map { if (it.id == kept.id) it.copy(inLibrary = true) else it }
                shown = shown.map { if (it.id == kept.id) it.copy(inLibrary = true) else it }
                val index = shown.indexOfFirst { it.id == kept.id }
                list.getChildAt(index)?.let { bindRow(it, shown[index]) }
                if (selectedId == kept.id) showHero(shown[index])
                if (selectedId == kept.id) {
                    val hadFocus = keepPill.isFocused
                    keepPill.visibility = View.GONE
                    if (hadFocus) playPill.requestFocus()
                }
                titlePage.item?.takeIf { it.id == kept.id }?.let { titlePage.show(it.copy(inLibrary = true)) }
                Toast.makeText(this, R.string.home_kept, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, getString(R.string.home_cant_keep, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- Side panel: settings and Other versions ------------------------------------------------

    private fun openSettings() {
        openPanel(settings, getString(R.string.settings_kicker), getString(R.string.settings_title))
    }

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
        if (event.action == KeyEvent.ACTION_DOWN && !panelOpen && !sheet.isOpen && !titlePage.isOpen && setup.visibility != View.VISIBLE) {
            if (search.isOpen && typeIntoSearch(event)) return true
            if (moveFocus(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
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

    /** The moves the layout can't work out from positions alone. */
    private fun moveFocus(keyCode: Int): Boolean {
        val focused = currentFocus ?: return false
        val inList = focused.parent === list
        val inTabs = focused.parent === tabsRow
        val inCatalogs = focused.parent === catalogsRow
        val inPills = focused.parent === pills
        val catalogTab = if (catalogsScroll.visibility == View.VISIBLE) catalogsRow.getChildAt(catalogIndex) else null
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> when {
                inList && list.indexOfChild(focused) == 0 -> catalogTab?.requestFocus() ?: focusActiveTab()
                inCatalogs || inPills -> focusActiveTab()
                else -> return false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                inTabs && search.isOpen -> return false
                inTabs && catalogTab != null -> catalogTab.requestFocus()
                inTabs || inCatalogs -> focusList()
                inPills -> Unit
                else -> return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inList) playPill.requestFocus() else return false
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                inList -> Unit
                inPills && focused === playPill -> focusList()
                else -> return false
            }
            else -> return false
        }
        return true
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
            search.isOpen -> if (!search.back()) closeSearch(focusMagnifier = true)
            setup.visibility == View.VISIBLE && pairing.back() -> Unit
            setup.visibility == View.VISIBLE && client != null -> {
                showSetup(false)
                focusList()
            }
            // From the tabs or the pills, Back returns to the list first; from the list it leaves as before.
            setup.visibility != View.VISIBLE && focused != null && focused.parent !== list && shown.isNotEmpty() -> focusList()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun wrap(top: Float = 0f, end: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Mercury.dp(body, top)
            marginEnd = Mercury.dp(body, end)
        }

    private companion object {
        val TABS = listOf(
            Tab(R.string.home_tab_continue, "continue"),
            Tab(R.string.home_tab_next, "next"),
            // New is new releases from the catalogs, paged like Browse (DATAFLOW.md, GET /new).
            Tab(R.string.home_tab_new, null, isNew = true),
            Tab(R.string.home_tab_movies, "movies"),
            Tab(R.string.home_tab_shows, "shows"),
            Tab(R.string.home_tab_browse, null),
        )
        const val LIST_WIDTH = 0.46f
        const val ROW_SIZE = 19f
        const val ROW_SIZE_ON = 30f
        /** A row's poster, 2:3. */
        const val POSTER_W_DP = 64f
        const val POSTER_H_DP = 96f
        /** How close to the end of a paged list the next page is fetched. */
        const val PAGE_AHEAD = 6
        const val ART_DELAY_MS = 140L
        const val PANEL_SLIDE = 0.08f
        const val STREAMS_TTL_MS = 10L * 60 * 1000
        const val SPACE_ART_ALPHA = 0.62f
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
