package com.thefactor1.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.meta.ArtworkLoader
import com.thefactor1.player.meta.MetaDetails
import com.thefactor1.player.meta.MetaQuery
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.resume.WatchHistory
import com.thefactor1.player.ui.Beacon
import com.thefactor1.player.ui.DefaultSettings
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.OptionList
import com.thefactor1.player.ui.PairingCard
import com.thefactor1.player.ui.ThisTv

/**
 * The home screen: the last film played, full screen with its art and a way back into it, the
 * other titles played lately along the bottom, and the settings for every video in a side panel.
 * Before anything has been played it says how to open a film in Player instead.
 */
class MainActivity : Activity() {

    private lateinit var fonts: Fonts
    private lateinit var history: WatchHistory
    private lateinit var defaults: DefaultSettings
    private lateinit var settings: OptionList
    private val heroArt by lazy { ArtworkLoader(this) }
    private val cardArt by lazy { ArtworkLoader(this) }
    private val facts by lazy { ThisTv.facts(this, PlayerPrefs(this)) }

    private lateinit var backdrop: ImageView
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var continueButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var cards: LinearLayout
    private lateinit var panel: View

    private var entries: List<WatchHistory.Entry> = emptyList()
    /** The title shown large; the rest are cards. */
    private var hero: WatchHistory.Entry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fonts = Fonts(this)
        history = WatchHistory(this)
        backdrop = findViewById(R.id.home_backdrop)
        logo = findViewById(R.id.home_logo)
        title = findViewById(R.id.home_title)
        continueButton = findViewById(R.id.home_continue)
        settingsButton = findViewById(R.id.home_settings)
        cards = findViewById(R.id.home_cards)
        panel = findViewById(R.id.settings_panel)

        fonts.apply(fonts.bold, title, findViewById(R.id.home_empty_title), findViewById(R.id.settings_title))
        fonts.apply(fonts.semibold, findViewById(R.id.home_kicker), findViewById(R.id.home_meta), findViewById(R.id.home_position),
            continueButton, settingsButton, findViewById(R.id.home_recent), findViewById(R.id.home_empty_kicker),
            findViewById(R.id.home_empty_steps), findViewById(R.id.home_empty_settings), findViewById(R.id.settings_kicker))
        fonts.apply(fonts.medium, findViewById(R.id.home_plot), findViewById(R.id.home_empty_body), findViewById(R.id.settings_hint))
        findViewById<TextView>(R.id.home_empty_steps).text =
            "1    ${getString(R.string.home_step_open)}\n2    ${getString(R.string.home_step_choose)}"

        defaults = DefaultSettings(this, PlayerPrefs(this)) { facts }
        settings = OptionList(this, findViewById(R.id.settings_items), findViewById(R.id.settings_hint), fonts, defaults::rows)
        continueButton.setOnClickListener { hero?.let(::openInSource) }
        settingsButton.setOnClickListener { openPanel() }
        findViewById<View>(R.id.home_empty_settings).setOnClickListener { openPanel() }
    }

    /** The phone app asking to connect while the home screen is up. */
    private val askToConnect: (String, (Boolean) -> Unit) -> Unit by lazy {
        val fonts = Fonts(this)
        val ask: (String, (Boolean) -> Unit) -> Unit = { device, answer -> PairingCard.ask(this, fonts, device, answer) }
        ask
    }

    override fun onStart() {
        super.onStart()
        PhoneCompanion.start(this)
        PhoneCompanion.approver = askToConnect
        Beacon.attach(this)
        PhoneCompanion.beaconScreen = Beacon
        // Back from a film: it's now the newest entry, so it takes the big spot.
        entries = history.all()
        hero = entries.firstOrNull()
        showHome()
        // The phone code may have changed since the list was built: a phone paired, or the network came up.
        if (panel.visibility == View.VISIBLE) settings.show(settings.focusedIndex())
    }

    override fun onStop() {
        super.onStop()
        if (PhoneCompanion.approver === askToConnect) PhoneCompanion.approver = null
        Beacon.detach(this)
        PhoneCompanion.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility == View.VISIBLE) {
            when (keyCode) {
                // Left and Right step the focused setting; they never leave the panel.
                KeyEvent.KEYCODE_DPAD_LEFT -> { settings.step(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { settings.step(1); return true }
                KeyEvent.KEYCODE_MENU -> { closePanel(); return true }
            }
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            openPanel()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Still delivered on every Android version this app supports without opting in to predictive back.")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) {
            closePanel()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---- The film -------------------------------------------------------------------------------

    private fun showHome() {
        val current = hero
        val empty = current == null
        listOf(R.id.home_hero, R.id.home_shelf, R.id.home_backdrop, R.id.home_scrim).forEach {
            findViewById<View>(it).visibility = if (empty) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.home_empty).visibility = if (empty) View.VISIBLE else View.GONE
        if (current == null) {
            if (panel.visibility != View.VISIBLE) findViewById<View>(R.id.home_empty_settings).requestFocus()
            return
        }
        showHero(current)
        showCards(current)
        if (panel.visibility != View.VISIBLE) {
            (if (continueButton.visibility == View.VISIBLE) continueButton else settingsButton).requestFocus()
        }
    }

    private fun showHero(entry: WatchHistory.Entry) {
        findViewById<TextView>(R.id.home_kicker).setText(if (entry.watched) R.string.home_watched_kicker else R.string.home_continue_kicker)
        title.text = entry.name
        title.visibility = View.VISIBLE
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
        showDetails(entry.episodeLine, entry.kicker ?: entry.year?.toString(), entry.description)

        val fill = findViewById<View>(R.id.home_progress_fill)
        fill.layoutParams = fill.layoutParams.apply { width = (resources.getDimension(R.dimen.home_progress_width) * fraction(entry)).toInt() }
        val app = entry.sourcePackage?.let(::appLabel)
        findViewById<TextView>(R.id.home_position).text = when {
            entry.watched && app != null -> getString(R.string.home_watched_from, app)
            entry.watched -> getString(R.string.home_watched)
            else -> getString(R.string.home_position, clock(entry.positionMs), timeLeft(entry))
        }
        continueButton.visibility = if (app != null) View.VISIBLE else View.GONE
        continueButton.text = app?.let { getString(if (entry.watched) R.string.home_open else R.string.home_continue, it) }

        backdrop.animate().cancel()
        backdrop.alpha = 0f
        backdrop.setImageDrawable(null)
        heroArt.cancel()
        val query = MetaQuery(entry.searchName ?: entry.name, entry.year, entry.season, entry.episode)
        heroArt.load(query, imdbHint = entry.imdbId, listener = object : ArtworkLoader.Listener {
            override fun onDetails(details: MetaDetails) {
                // Fills in what playback didn't get to: a title that was left before it was recognised.
                if (entry.description == null) showDetails(details.episodeLine, details.kicker, details.description)
            }

            override fun onBackdrop(bitmap: Bitmap) {
                val sharpening = backdrop.drawable != null
                backdrop.setImageBitmap(bitmap)
                if (!sharpening) backdrop.animate().alpha(1f).setDuration(ART_FADE_MS).start()
            }

            override fun onLogo(bitmap: Bitmap) {
                logo.setImageBitmap(bitmap)
                logo.alpha = 0f
                logo.visibility = View.VISIBLE
                logo.animate().alpha(1f).setDuration(ART_FADE_MS).start()
                title.visibility = View.GONE
            }
        })
    }

    private fun showDetails(episodeLine: String?, kicker: String?, description: String?) {
        val meta = findViewById<TextView>(R.id.home_meta)
        meta.text = listOfNotNull(episodeLine, kicker).joinToString("  ·  ")
        meta.visibility = if (meta.text.isEmpty()) View.GONE else View.VISIBLE
        val plot = findViewById<TextView>(R.id.home_plot)
        plot.text = description
        plot.visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun showCards(current: WatchHistory.Entry) {
        cards.removeAllViews()
        val rest = entries.filter { it.key != current.key }.take(MAX_CARDS)
        findViewById<View>(R.id.home_recent).visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        val widthPx = resources.getDimensionPixelSize(R.dimen.home_card_width)
        rest.forEach { entry ->
            val card = inflater.inflate(R.layout.item_home_card, cards, false)
            card.clipToOutline = true
            card.id = View.generateViewId()
            card.nextFocusUpId = R.id.home_continue.takeIf { continueButton.visibility == View.VISIBLE } ?: R.id.home_settings
            val name = card.findViewById<TextView>(R.id.card_title)
            name.typeface = fonts.bold
            name.text = entry.name
            val progress = card.findViewById<View>(R.id.card_progress)
            progress.visibility = if (entry.watched) View.GONE else View.VISIBLE
            val fill = card.findViewById<View>(R.id.card_progress_fill)
            fill.layoutParams = fill.layoutParams.apply { width = ((widthPx - 20 * resources.displayMetrics.density) * fraction(entry)).toInt() }
            val art = card.findViewById<ImageView>(R.id.card_art)
            entry.imdbId?.let { id -> cardArt.backdrop(id, widthPx) { art.setImageBitmap(it) } }
            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) CARD_LIFT else 1f).scaleY(if (hasFocus) CARD_LIFT else 1f).setDuration(CARD_LIFT_MS).start()
            }
            // OK brings the card up as the big one, ready to continue.
            card.setOnClickListener {
                hero = entry
                showHome()
            }
            cards.addView(card)
        }
        // Down from the buttons lands on the first card, not the one under the cursor.
        cards.getChildAt(0)?.let { first ->
            continueButton.nextFocusDownId = first.id
            settingsButton.nextFocusDownId = first.id
        }
    }

    /**
     * Back into the app the title was played from, which hands it to Player again; the saved
     * position does the rest. Stremio goes straight to the film or episode; other apps just open.
     */
    private fun openInSource(entry: WatchHistory.Entry) {
        val pkg = entry.sourcePackage ?: return
        val id = entry.imdbId
        if (id != null && pkg.contains("stremio")) {
            val type = if (entry.season != null) "series" else "movie"
            val video = if (entry.season != null && entry.episode != null) "$id:${entry.season}:${entry.episode}" else id
            val detail = Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/$type/$id/$video")).setPackage(pkg)
            try {
                startActivity(detail)
                return
            } catch (e: ActivityNotFoundException) {
                // An older Stremio without the link: open it normally.
            }
        }
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        try {
            startActivity(launch ?: throw ActivityNotFoundException())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.home_no_app, appLabel(pkg) ?: pkg), Toast.LENGTH_SHORT).show()
        }
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        null
    }

    private fun fraction(entry: WatchHistory.Entry): Float = when {
        entry.watched -> 1f
        entry.durationMs <= 0 -> 0f
        else -> (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
    }

    /** "1:12:40", or "31:05" under an hour. */
    private fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun timeLeft(entry: WatchHistory.Entry): String {
        val minutes = ((entry.durationMs - entry.positionMs).coerceAtLeast(0) / 60_000).toInt().coerceAtLeast(1)
        return if (minutes >= 60) getString(R.string.home_left_hm, minutes / 60, minutes % 60) else getString(R.string.home_left_m, minutes)
    }

    // ---- Settings panel -------------------------------------------------------------------------

    private fun openPanel(focusIndex: Int = -1) {
        findViewById<View>(R.id.panel_scrim).visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.translationX = panel.width * PANEL_SLIDE
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(PANEL_MS).start()
        settings.show(focusIndex)
    }

    private fun closePanel() {
        findViewById<View>(R.id.panel_scrim).visibility = View.GONE
        panel.animate().cancel()
        panel.visibility = View.INVISIBLE
        (if (hero == null) findViewById<View>(R.id.home_empty_settings) else settingsButton).requestFocus()
    }

    private companion object {
        const val MAX_CARDS = 8
        const val ART_FADE_MS = 320L
        const val PANEL_MS = 180L
        const val PANEL_SLIDE = 0.08f
        const val CARD_LIFT = 1.06f
        const val CARD_LIFT_MS = 140L
    }
}
