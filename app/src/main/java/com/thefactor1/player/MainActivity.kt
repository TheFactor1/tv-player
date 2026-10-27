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
import com.thefactor1.player.home.HomePlay
import com.thefactor1.player.home.Mercury
import com.thefactor1.player.home.PairingSetup
import com.thefactor1.player.home.Refine
import com.thefactor1.player.home.RefineSheet
import com.thefactor1.player.home.SearchScreen
import com.thefactor1.player.home.TitlePage
import com.thefactor1.player.hub.Catalog
import com.thefactor1.player.hub.FoundHub
import com.thefactor1.player.hub.HubClient
import com.thefactor1.player.hub.HubItem
import com.thefactor1.player.hub.HubStream
import com.thefactor1.player.hub.HubUnauthorized
import com.thefactor1.player.hub.HubVault
import com.thefactor1.player.hub.NextEpisode
import com.thefactor1.player.hub.PlayInfo
import com.thefactor1.player.hub.Quality
import com.thefactor1.player.launch.IntentContract
import com.thefactor1.player.meta.ArtworkLoader
import com.thefactor1.player.meta.MetaDetails
import com.thefactor1.player.meta.MetaQuery
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
    private val heroArt by lazy { ArtworkLoader(this) }
    private val cardArt by lazy { ArtworkLoader(this) }
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
    private var artFirst = true
    private var frontIsB = false
    private var logo: Pair<String, Bitmap>? = null
    private var panelList: OptionList? = null
    private var panelOpen = false
    private var beforePanel: View? = null
    private var beforeTitle: View? = null
    /** What the player is playing for us, so its position goes back to the hub. */
    private var playing: Pair<String, NextEpisode?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fonts = Fonts(this)
        prefs = PlayerPrefs(this)
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
        findViewById<View>(R.id.home_fade).background = Mercury.backdropFade(this)
        scroll.layoutParams.width = (resources.displayMetrics.widthPixels * LIST_WIDTH).toInt()

        fonts.apply(fonts.ui, message, playPill, keepPill, findViewById(R.id.home_details))
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
        titlePage = TitlePage(findViewById(R.id.home_title_page), fonts, ::play, ::showVersions, ::keep)
        search = SearchScreen(findViewById(R.id.home_search), fonts, cardArt,
            { query, done -> client?.run({ this.search(query) }, done) },
            { showArt(it) },
            ::openTitle)
        sheet = RefineSheet(findViewById(R.id.home_refine), fonts, ::onRefine) {
            body.animate().translationX(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit).start()
            filterTab.requestFocus()
        }

        playPill.setOnClickListener { selected()?.let(::play) }
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
            if (view !== magnifier) view.background = if (active) getDrawable(R.drawable.mercury_meniscus) else null
        }
        filterTab.text = if (refine.count > 0) getString(R.string.home_filter_on, refine.count) else getString(R.string.home_filter)
        filterTab.visibility = if (TABS[tabIndex].refinable && !search.isOpen) View.VISIBLE else View.GONE
        for (i in 0 until catalogsRow.childCount) {
            val view = catalogsRow.getChildAt(i)
            val active = i == catalogIndex
            view.alpha = if (active || view.isFocused) 1f else Mercury.TAB_REST
            view.background = if (active) getDrawable(R.drawable.mercury_meniscus) else null
        }
    }

    private fun buildCatalogTabs() {
        catalogsRow.removeAllViews()
        catalogs.forEachIndexed { index, catalog ->
            val view = Mercury.text(this, fonts.ui, 14f).apply {
                text = catalog.name
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

    private fun row(item: HubItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        addView(Mercury.text(this@MainActivity, fonts.display, ROW_SIZE, tracking = -0.03f).apply { text = item.title })
        val credits = Mercury.text(this@MainActivity, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.14f).apply {
            text = Mercury.credits(item, showLibrary = TABS[tabIndex].collection == null)
            visibility = View.GONE
        }
        addView(credits, wrap(top = 8f))
        alpha = Mercury.ROW_REST
        val gap = Mercury.dp(this, 13f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap }
        setOnFocusChangeListener { v, has -> if (has) select(list.indexOfChild(v)) }
        setOnClickListener { openTitle(item) }
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
        }
        keepPill.visibility = if (item.inLibrary) View.GONE else View.VISIBLE
        val row = list.getChildAt(index) ?: return
        row.post { scroll.smoothScrollTo(0, (row.top - scroll.height / 4).coerceAtLeast(0)) }
        if (TABS[tabIndex].collection == null && !exhausted && !loading && index >= shown.size - PAGE_AHEAD) load(reset = false)
    }

    private fun style(row: View, on: Boolean) {
        val group = row as LinearLayout
        val name = group.getChildAt(0) as TextView
        name.textSize = if (on) ROW_SIZE_ON else ROW_SIZE
        name.maxLines = if (on) 2 else 1
        group.getChildAt(1).visibility = if (on) View.VISIBLE else View.GONE
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

    // shortcut: art comes from Cinemeta/metahub by IMDb id (the existing loader and its disk cache), not the hub's own URLs; switch if the hub ever serves non-IMDb ids or its own art.
    private fun loadArtNow(item: HubItem) {
        artId = item.id
        artFirst = true
        heroArt.load(MetaQuery(item.title, item.year, null, null), imdbHint = item.id, listener = object : ArtworkLoader.Listener {
            override fun onDetails(details: MetaDetails) = Unit

            override fun onBackdrop(bitmap: Bitmap) {
                if (artFirst) crossfade(bitmap) else (if (frontIsB) artB else artA).setImageBitmap(bitmap)
                artFirst = false
            }

            override fun onLogo(bitmap: Bitmap) {
                logo = item.id to bitmap
                if (titlePage.item?.id == item.id) titlePage.showLogo(bitmap)
            }
        })
    }

    /** The new art fades in over the old, or the old fades off the new, so there is never a gap to black. */
    private fun crossfade(bitmap: Bitmap) {
        artA.animate().cancel()
        artB.animate().cancel()
        if (frontIsB) {
            artA.setImageBitmap(bitmap)
            artA.alpha = 1f
            artB.animate().alpha(0f).setDuration(Mercury.ART_FADE_MS).setInterpolator(Motion.enter).start()
        } else {
            artB.setImageBitmap(bitmap)
            artB.alpha = 0f
            artB.animate().alpha(1f).setDuration(Mercury.ART_FADE_MS).setInterpolator(Motion.enter).start()
        }
        frontIsB = !frontIsB
    }

    // ---- Title page, Search, Refine -------------------------------------------------------------

    private fun openTitle(item: HubItem) {
        if (!titlePage.isOpen) beforeTitle = currentFocus
        showArt(item)
        titlePage.open(item)
        logo?.takeIf { it.first == item.id }?.let { titlePage.showLogo(it.second) }
        hideChrome(true)
        // The list and search carry less than the full record; fill in the overview and badges.
        client?.run({ meta(item) }) done@{ result ->
            val fresh = result.getOrNull() ?: return@done
            if (titlePage.item?.id != item.id) return@done
            titlePage.show(fresh.copy(inLibrary = fresh.inLibrary || item.inLibrary, resume = fresh.resume ?: item.resume, nextEpisode = fresh.nextEpisode ?: item.nextEpisode))
        }
    }

    private fun closeTitle() {
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
        body.visibility = View.INVISIBLE
        catalogsScroll.visibility = View.GONE
        paintTabs()
    }

    private fun closeSearch(focusMagnifier: Boolean) {
        search.close()
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
        body.animate().translationX(-Mercury.dp(body, Motion.RISE_DP * 2.4f).toFloat()).setDuration(Motion.ENTER_MS).setInterpolator(Motion.enter).start()
        sheet.open(refine, all, (if (frontIsB) artB else artA).drawable)
    }

    private fun onRefine(next: Refine) {
        refine = next
        homePrefs.edit { putString(KEY_REFINE, next.encode()) }
        render()
        paintTabs()
    }

    // ---- Play, Other versions, Keep this --------------------------------------------------------

    private fun play(item: HubItem) = startPlay(item, null)

    /** Asks the hub for the best stream now (or plays [stream]) and hands it to the player the way any app would. */
    private fun startPlay(item: HubItem, stream: HubStream?) {
        val hub = client ?: return
        val episode = HomePlay.episodeFor(item)
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
                Toast.makeText(this, getString(R.string.home_cant_play, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
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
        hub.run({ saveResume(id, episode, position, duration) }) { load(reset = true) }
    }

    private fun showVersions(item: HubItem) {
        val hub = client ?: return
        hub.run({ streams(item, HomePlay.episodeFor(item)) }) done@{ result ->
            val streams = result.getOrNull().orEmpty()
            if (streams.isEmpty()) {
                Toast.makeText(this, result.exceptionOrNull()?.message ?: getString(R.string.home_versions_none), Toast.LENGTH_SHORT).show()
                return@done
            }
            val versions = OptionList(this, findViewById(R.id.settings_items), findViewById(R.id.settings_hint), fonts) {
                streams.map { stream ->
                    OptionList.RowSpec.Item {
                        Option(stream.label(), stream.badges.cut, stream.description ?: stream.source) {
                            closePanel()
                            startPlay(item, stream)
                        }
                    }
                }
            }
            openPanel(versions, getString(R.string.home_versions_kicker), item.title)
        }
    }

    private fun keep(item: HubItem) {
        val hub = client ?: return
        hub.run({ this.keep(item) }) { result ->
            result.onSuccess { kept ->
                all = all.map { if (it.id == kept.id) it.copy(inLibrary = true) else it }
                shown = shown.map { if (it.id == kept.id) it.copy(inLibrary = true) else it }
                val index = shown.indexOfFirst { it.id == kept.id }
                (list.getChildAt(index) as? LinearLayout)?.let { row ->
                    (row.getChildAt(1) as TextView).text = Mercury.credits(shown[index], showLibrary = true)
                }
                if (selectedId == kept.id) {
                    val hadFocus = keepPill.isFocused
                    keepPill.visibility = View.GONE
                    if (hadFocus) playPill.requestFocus()
                }
                titlePage.item?.takeIf { it.id == kept.id }?.let { titlePage.show(it.copy(inLibrary = true)) }
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
    }

    // ---- Remote ---------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        const val ROW_SIZE = 21f
        const val ROW_SIZE_ON = 42f
        /** How close to the end of a paged list the next page is fetched. */
        const val PAGE_AHEAD = 6
        const val ART_DELAY_MS = 140L
        const val PANEL_SLIDE = 0.08f
        const val REQUEST_PLAY = 1
        const val KEY_REFINE = "refine"
        const val KEY_TAB = "tab"
        const val KEY_CATALOG = "catalog"
        const val STATE_PLAYING_ID = "playing_id"
        const val STATE_PLAYING_SEASON = "playing_season"
        const val STATE_PLAYING_EPISODE = "playing_episode"
    }
}
