package com.thefactor1.player.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Display
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.thefactor1.player.BuildConfig
import com.thefactor1.player.R
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.companion.CompanionHost
import com.thefactor1.player.companion.CompanionPerson
import com.thefactor1.player.meta.ArtworkLoader
import com.thefactor1.player.meta.CastLoader
import com.thefactor1.player.meta.CastMember
import com.thefactor1.player.meta.MetaDetails
import com.thefactor1.player.meta.MetaQuery
import com.thefactor1.player.meta.OnlineExtras
import com.thefactor1.player.meta.OnlineSubtitle
import com.thefactor1.player.meta.SubtitleLanguages
import com.thefactor1.player.playback.AudioEnhancement
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.playback.StatsOverlay
import com.thefactor1.player.playback.SubtitleDelay
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlin.math.abs

/**
 * The Cinema look: edge-to-edge scrims, a remote-first control bar of round icon buttons and side
 * panels for tracks and picture. Nothing is drawn over the video unless asked for, and nothing
 * updates while hidden. Before playback, and after a while paused, the meta screen shows the
 * film's backdrop, logo and details from Stremio's catalogue.
 *
 * When the film is recognised, it also offers "Skip intro" and subtitles from OpenSubtitles.
 *
 * Remote, with controls hidden: OK pauses (or presses "Skip intro" while it shows), Left/Right skip 10 s back / 30 s forward, Up shows the
 * controls, Down opens subtitles, Back returns to the source app, Menu opens More. Back with
 * controls or a panel showing closes them.
 */
@OptIn(UnstableApi::class)
class CinemaController(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val prefs: PlayerPrefs,
    private val stats: StatsOverlay,
    private val enhancement: AudioEnhancement,
    private val subtitleDelay: SubtitleDelay,
    private val onExit: () -> Unit,
) : CompanionHost {
    private enum class Panel { AUDIO, SUBTITLES, PICTURE, MORE }

    /** One line of a menu: a track or mode to pick, a toggle, a stepped setting, or a list one level down. */
    private class Item(
        val label: String,
        /** Beside the name: a track's format, or a setting's current value. */
        val value: String?,
        val checked: Boolean,
        /** Toggles keep the panel open and update in place; picking a track or a mode closes it. */
        val keepOpen: Boolean = false,
        /** A list one level down, for choices that come and go (subtitles found online). */
        val opens: Page? = null,
        /** A setting with a range of values, stepped with Left and Right on its own row. */
        val adjust: Adjust? = null,
        /** Drawn as an on/off switch rather than a check. */
        val switch: Boolean = false,
        /** What the row does, shown in the line under the list while it has focus. */
        val hint: String? = null,
        /** A section name drawn above this row. */
        val section: String? = null,
        val action: () -> Unit = {},
    )

    /** Where a stepped setting stands ([index] of [count]) and how to move it. */
    private class Adjust(val index: Int, val count: Int, val set: (Int) -> Unit)

    /** A list of choices one level down; picking one applies it and returns to the panel. */
    private class Page(val title: String, val items: () -> List<Item>)

    private val fonts = Fonts(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val density = activity.resources.displayMetrics.density

    private val infoBar: View = activity.findViewById(R.id.info_bar)
    private val controlBar: View = activity.findViewById(R.id.control_bar)
    private val sidePanel: View = activity.findViewById(R.id.side_panel)
    private val panelItems: LinearLayout = activity.findViewById(R.id.panel_items)
    private val panelKicker: TextView = activity.findViewById(R.id.panel_kicker)
    private val panelTitle: TextView = activity.findViewById(R.id.panel_title)
    private val panelHint: TextView = activity.findViewById(R.id.panel_hint)
    private val title: TextView = activity.findViewById(R.id.title)
    private val source: TextView = activity.findViewById(R.id.source)
    private val badges: LinearLayout = activity.findViewById(R.id.badges)
    private val clock: TextView = activity.findViewById(R.id.clock)
    private val endsAt: TextView = activity.findViewById(R.id.ends_at)
    private val elapsed: TextView = activity.findViewById(R.id.elapsed)
    private val remaining: TextView = activity.findViewById(R.id.remaining)
    private val seekBar: SeekBarView = activity.findViewById(R.id.seek_bar)
    private val playButton: ImageView = activity.findViewById(R.id.btn_play)
    private val audioButton: ImageView = activity.findViewById(R.id.btn_audio)
    private val subtitlesButton: ImageView = activity.findViewById(R.id.btn_subtitles)
    private val pictureButton: ImageView = activity.findViewById(R.id.btn_picture)
    private val moreButton: ImageView = activity.findViewById(R.id.btn_more)
    private val focusLabel: TextView = activity.findViewById(R.id.focus_label)
    private val toast: TextView = activity.findViewById(R.id.center_toast)
    private val exitHint: TextView = activity.findViewById(R.id.exit_hint)
    private val centerGlyph: ImageView = activity.findViewById(R.id.center_glyph)
    private val skipBack: View = activity.findViewById(R.id.skip_back)
    private val skipForward: View = activity.findViewById(R.id.skip_forward)
    private val skipBackAmount: TextView = activity.findViewById(R.id.skip_back_amount)
    private val skipForwardAmount: TextView = activity.findViewById(R.id.skip_forward_amount)
    private val miniBar: View = activity.findViewById(R.id.mini_bar)
    private val miniSeek: SeekBarView = activity.findViewById(R.id.mini_seek)
    private val miniTime: TextView = activity.findViewById(R.id.mini_time)
    private val loadingCard: View = activity.findViewById(R.id.loading_card)
    private val loadingTitle: TextView = activity.findViewById(R.id.loading_title)
    private val loadingLine: TextView = activity.findViewById(R.id.loading_line)
    private val loadingSource: TextView = activity.findViewById(R.id.loading_source)
    private val loadingSpinner: View = activity.findViewById(R.id.loading_spinner)
    private val metaBackdrop: ImageView = activity.findViewById(R.id.meta_backdrop)
    private val metaClock: TextView = activity.findViewById(R.id.meta_clock)
    private val metaKicker: TextView = activity.findViewById(R.id.meta_kicker)
    private val metaLogo: ImageView = activity.findViewById(R.id.meta_logo)
    private val metaEpisode: TextView = activity.findViewById(R.id.meta_episode)
    private val metaDescription: TextView = activity.findViewById(R.id.meta_description)
    private val errorActions: View = activity.findViewById(R.id.error_actions)
    private val retryButton: TextView = activity.findViewById(R.id.btn_retry)
    private val otherPlayerButton: TextView = activity.findViewById(R.id.btn_other_player)
    private val metaBlock: View = activity.findViewById(R.id.meta_block)
    private val preShow = PreShow(activity.findViewById(R.id.booth), fonts, ::displayInfo)
    /** In the booth the backdrop is only a grey ghost behind the log. */
    private val boothArt = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    /** The error card's two ways out. */
    var onRetry: (() -> Unit)? = null
    var onOpenElsewhere: (() -> Unit)? = null
    /**
     * A speed other than 1× was picked but the audio is passed through, which can only play at 1×,
     * so the sink put the speed back. The player answers by switching to decoded audio, then
     * applies the speed it is given again.
     */
    var onSpeedNeedsDecodedAudio: ((speed: Float) -> Unit)? = null
    /** The lip-sync correction changed, in ms. */
    var onAudioDelayChanged: ((Int) -> Unit)? = null
    /** Night mode or dialogue boost was switched on while audio is passed through, so it must be decoded. */
    var onNeedsDecodedAudio: (() -> Unit)? = null
    /** "Skip credits" at the end of the video: finish as if it had played out. */
    var onSkipCredits: (() -> Unit)? = null
    /** Add a subtitle file from the web as a track and switch to it. */
    var onAddSubtitle: ((url: String, language: String?, label: String) -> Unit)? = null

    private var player: ExoPlayer? = null
    private var controlsVisible = false
    private var exitArmedAt = 0L
    private var panel: Panel? = null
    private var panelOpener: View? = null
    private var page: Page? = null
    private var pageOpenerIndex = -1
    private var loading = true
    private var errorShown = false
    private var sourceApp: String? = null
    private var firstFrameRendered = false
    private val artwork = ArtworkLoader(activity)
    private var artworkQuery: MetaQuery? = null
    private var artworkStartedAt = 0L
    /** The art lookup has given all it will (a logo, or nothing), or the wait for it ran out. */
    private var artSettled = false
    /** The film is held on its first frame behind the pre-show until [canReveal]. */
    private var revealHeld = false
    private var boothStartedAt = 0L
    private var holdStartedAt = 0L
    private var logoShownAt = 0L
    private var details: MetaDetails? = null
    /** The detail the source app gave ("S1 E1", "2024"), shown until the catalogue says more. */
    private var launchDetail: String? = null
    private var pauseScreenShown = false
    private val extras = OnlineExtras()
    private val cast = CastLoader(activity, BuildConfig.TMDB_API_KEY)
    private val infoCast = CastRow(activity.findViewById(R.id.info_cast), fonts, faceDp = 36, nameSp = 14f, maxPeople = 4)
    private val metaCast = CastRow(activity.findViewById(R.id.meta_cast), fonts, faceDp = 48, nameSp = 15f, maxPeople = 5)
    private val metaCastView: View = activity.findViewById(R.id.meta_cast)
    private var castShown: List<CastMember> = emptyList()
    private val skipPrompt = SkipPrompt(activity.findViewById(R.id.skip_prompt)) { onSkipCredits?.invoke() }
    private var onlineSearch = OnlineSearch.UNMATCHED
    private var onlineSubtitles: List<OnlineSubtitle> = emptyList()

    private enum class OnlineSearch { UNMATCHED, SEARCHING, DONE }

    // Keys handled on press have their release swallowed too, so nothing behind them reacts.
    private var swallowReleaseOf = KeyEvent.KEYCODE_UNKNOWN
    private var centerPressedWhileHidden = false
    private var skipTotalMs = 0L
    private var skipShownUntil = 0L
    private var speedIndex = 0
    // Stalls while playing faster than 1×; seeks aren't stalls, so the last one is remembered.
    private var fastStalls = 0
    private var lastSeekAtMs = 0L

    init {
        fonts.apply(fonts.bold, title, panelTitle, loadingTitle, skipBackAmount, skipForwardAmount, metaKicker)
        fonts.apply(fonts.semibold, activity.findViewById<TextView>(R.id.skip_prompt), clock, metaClock, endsAt, elapsed, remaining, panelKicker, toast, focusLabel, miniTime,
            metaEpisode, loadingLine, retryButton, otherPlayerButton)
        fonts.apply(fonts.medium, source, loadingSource, metaDescription, exitHint, panelHint)
        retryButton.setOnClickListener { onRetry?.invoke() }
        otherPlayerButton.setOnClickListener { onOpenElsewhere?.invoke() }

        playButton.setOnClickListener { togglePlayPause(); scheduleHide() }
        audioButton.setOnClickListener { openPanel(Panel.AUDIO, it) }
        subtitlesButton.setOnClickListener { openPanel(Panel.SUBTITLES, it) }
        pictureButton.setOnClickListener { openPanel(Panel.PICTURE, it) }
        moreButton.setOnClickListener { openPanel(Panel.MORE, it) }
        for (button in listOf(playButton, audioButton, subtitlesButton, pictureButton, moreButton)) {
            button.setOnFocusChangeListener { view, hasFocus -> if (hasFocus) labelFocused(view) }
        }
        // The skip progress line only shows position; it never takes focus.
        miniSeek.isFocusable = false
        seekBar.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) labelFocused(null) }
        seekBar.onScrub = { delta ->
            seekBy(delta)
            updateProgress()
            scheduleHide()
        }

        playerView.resizeMode = prefs.resizeMode
        applySubtitleStyle()
    }

    /** [detail] is the episode or year shown under the title, e.g. "S1 E1". */
    /** [imdbId] is one the source app sent with the stream, which lets the art skip the name search. */
    /** The phone app asking to connect, put to whoever is watching. */
    private val askToConnect: (String, (Boolean) -> Unit) -> Unit = { device, answer -> PairingCard.ask(activity, fonts, device, answer) }

    fun attach(exo: ExoPlayer, titleText: String?, detail: String?, sourceApp: String?, imdbId: String? = null) {
        player = exo
        exo.addListener(listener)
        this.sourceApp = sourceApp
        title.text = titleText.orEmpty()
        launchDetail = detail
        showDetails()
        loadArtwork(MetaQuery.from(titleText, detail), imdbId)
        val sourceLine = listOfNotNull(detail, sourceApp?.let { activity.getString(R.string.source_from, it) }).joinToString("  ·  ")
        source.text = sourceLine
        source.visibility = if (sourceLine.isEmpty()) View.GONE else View.VISIBLE
        loadingSource.text = activity.getString(R.string.loading_source, sourceApp ?: activity.getString(R.string.another_app))
        exo.setPlaybackSpeed(SPEEDS[speedIndex])
        updatePlayButton()
        updateBadges(exo.currentTracks)
        preShow.attach(exo, sourceApp)
        skipPrompt.attach(exo)
        updateSkipPrompt()
        PhoneCompanion.host = this
        PhoneCompanion.approver = askToConnect
    }

    fun detach() {
        if (PhoneCompanion.host === this) PhoneCompanion.host = null
        if (PhoneCompanion.approver === askToConnect) PhoneCompanion.approver = null
        player?.removeListener(listener)
        player = null
        handler.removeCallbacksAndMessages(null)
        revealHeld = false
        hidePauseScreen(animate = false)
        skipPrompt.detach()
        preShow.detach()
    }

    // ---- Meta screen: film art and details, for loading and for the pause screen ---------------

    /** Re-attaching to the same film (after Home and back) keeps the art already on screen. */
    private fun loadArtwork(query: MetaQuery?, imdbId: String?) {
        if (query == artworkQuery) return
        artworkQuery = query
        artwork.cancel()
        artSettled = false
        logoShownAt = 0L
        extras.cancel()
        cast.cancel()
        castShown = emptyList()
        PhoneCompanion.reset()
        PhoneCompanion.loadTranscript(PhoneCompanion.launchSubtitles)
        infoCast.clear()
        metaCast.clear()
        skipPrompt.setSegments(emptyList())
        onlineSearch = OnlineSearch.UNMATCHED
        onlineSubtitles = emptyList()
        details = null
        metaBackdrop.animate().cancel()
        metaBackdrop.alpha = 0f
        metaBackdrop.setImageDrawable(null)
        metaLogo.setImageDrawable(null)
        metaLogo.visibility = View.GONE
        preShow.setLogo(null)
        setAccent(ContextCompat.getColor(activity, R.color.ink))
        loadingTitle.visibility = if (loadingTitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        showDetails()
        if (query == null) return
        artworkStartedAt = SystemClock.uptimeMillis()
        artwork.load(query, imdbHint = imdbId, listener = object : ArtworkLoader.Listener {
            override fun onDetails(details: MetaDetails) {
                this@CinemaController.details = details
                showDetails()
                details.imdbId?.let { loadExtras(it, query) }
            }

            override fun onBackdrop(bitmap: Bitmap) {
                // The full backdrop takes the small one's place in the same frame, with no second fade.
                val sharpening = metaBackdrop.drawable != null
                metaBackdrop.setImageBitmap(bitmap)
                if (!sharpening) {
                    metaBackdrop.animate().alpha(if (preShow.isShowing) BOOTH_ART_ALPHA else 1f)
                        .setDuration(artFadeMs()).setInterpolator(easeOut).start()
                }
            }

            override fun onAccent(color: Int) {
                if (prefs.filmColours) setAccent(color)
            }

            override fun onLogo(bitmap: Bitmap) {
                metaLogo.setImageBitmap(bitmap)
                metaLogo.alpha = 0f
                metaLogo.visibility = View.VISIBLE
                metaLogo.animate().alpha(1f).setDuration(artFadeMs()).start()
                loadingTitle.visibility = View.GONE
                preShow.setLogo(bitmap, artFadeMs())
                logoShownAt = SystemClock.uptimeMillis()
            }

            override fun onNoArt() {
                artSettled = true
            }
        })
    }

    /** The art lookup is still out and could yet put the film's logo on the pre-show. */
    private fun artPending(): Boolean = artworkQuery != null && !artSettled && metaLogo.visibility != View.VISIBLE

    /**
     * The pre-show is part of the show, not just a wait: it stays up for at least [MIN_BOOTH_MS]
     * so the log types in and the cue mark's flash and cut are always seen. On a first play it
     * also waits up to [ART_WAIT_MS] for the logo, which then stays a moment before the cut.
     */
    private fun canReveal(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (artPending() && now - holdStartedAt >= ART_WAIT_MS) artSettled = true
        return now - boothStartedAt >= MIN_BOOTH_MS &&
            !artPending() &&
            (logoShownAt == 0L || now - logoShownAt >= ART_LOGO_BEAT_MS)
    }

    /** Holds the film on its first frame, behind the pre-show, until [canReveal]. */
    private fun holdReveal() {
        revealHeld = true
        holdStartedAt = SystemClock.uptimeMillis()
        player?.pause()
        handler.removeCallbacks(checkReveal)
        handler.postDelayed(checkReveal, REVEAL_CHECK_MS)
    }

    private val checkReveal: Runnable = object : Runnable {
        override fun run() {
            if (!revealHeld) return
            if (!canReveal()) {
                handler.postDelayed(this, REVEAL_CHECK_MS)
                return
            }
            revealHeld = false
            // Playing again calls hideLoading, which now reveals.
            player?.play()
        }
    }

    /** Art that was already on the box comes up almost at once; art that took a while eases in. */
    private fun artFadeMs(): Long =
        if (SystemClock.uptimeMillis() - artworkStartedAt < ART_QUICK_WINDOW_MS) ART_QUICK_FADE_MS else ART_FADE_MS

    /** The film's own colour on the seek bars; white until (and unless) its backdrop lends one. */
    private fun setAccent(color: Int) {
        seekBar.setProgressColor(color)
        miniSeek.setProgressColor(color)
    }

    /** Skip times and online subtitles for the recognised film or episode. */
    private fun loadExtras(imdbId: String, query: MetaQuery) {
        cast.load(imdbId, query.season, query.episode, object : CastLoader.Listener {
            override fun onCast(people: List<CastMember>) {
                castShown = people
                PhoneCompanion.cast = people.map { CompanionPerson(it.name, it.character, it.photoPath?.let { path -> TMDB_PHOTOS + path }) }
                infoCast.show(people)
                metaCast.show(people)
                showDetails()
            }

            override fun onPhoto(member: CastMember, bitmap: Bitmap) {
                infoCast.setPhoto(member, bitmap)
                metaCast.setPhoto(member, bitmap)
            }
        })
        extras.skipSegments(imdbId, query.season, query.episode) { skipPrompt.setSegments(it) }
        onlineSearch = OnlineSearch.SEARCHING
        refreshSubtitlePanel()
        extras.subtitles(imdbId, query.season, query.episode) { found ->
            onlineSubtitles = found
            val preferred = listOf(prefs.subtitleLanguage.ifEmpty { Locale.getDefault().language }, "en")
            // The launching app's own files first (they match this release), then the best few found online.
            PhoneCompanion.loadTranscript(PhoneCompanion.launchSubtitles + SubtitleLanguages.rank(found, preferred, TRANSCRIPT_TRIES).map { it.url })
            onlineSearch = OnlineSearch.DONE
            refreshSubtitlePanel()
        }
    }

    private fun refreshSubtitlePanel() {
        if (panel == Panel.SUBTITLES && page == null) buildPanel(focusIndex = focusedIndex())
    }

    /** The skip button gives way to anything else on screen. */
    private fun updateSkipPrompt() {
        skipPrompt.blocked = loading || controlsVisible || panel != null || pauseScreenShown
    }

    private fun showDetails() {
        val known = details
        if (metaLogo.visibility != View.VISIBLE) {
            loadingTitle.text = title.text
            loadingTitle.visibility = if (title.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val kicker = known?.kicker ?: launchDetail?.takeIf { artworkQuery?.isSeries != true }
        metaKicker.text = kicker?.uppercase()
        metaKicker.visibility = if (kicker.isNullOrEmpty()) View.GONE else View.VISIBLE
        val episode = known?.episodeLine ?: launchDetail?.takeIf { artworkQuery?.isSeries == true }
        metaEpisode.text = episode
        metaEpisode.visibility = if (episode.isNullOrEmpty()) View.GONE else View.VISIBLE
        metaDescription.text = known?.description
        // The synopsis is for the pause screen; while loading, the status line has the floor.
        metaDescription.visibility = if (pauseScreenShown && !known?.description.isNullOrEmpty()) View.VISIBLE else View.GONE
        metaCastView.visibility = if (pauseScreenShown && castShown.isNotEmpty()) View.VISIBLE else View.GONE
        preShow.setHeader(title.text, kicker, episode)
    }

    /** After a while paused, the picture gives way to the film's art and synopsis, like a TV app's detail page. */
    private val showPauseScreen = Runnable {
        val exo = player ?: return@Runnable
        if (loading || panel != null || exo.playWhenReady || exo.playbackState == Player.STATE_ENDED) return@Runnable
        hideControls()
        pauseScreenShown = true
        updateSkipPrompt()
        showDetails()
        loadingSpinner.visibility = View.GONE
        loadingSource.visibility = View.GONE
        metaClock.visibility = View.VISIBLE
        metaClock.text = DateFormat.getTimeFormat(activity).format(Date())
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        loadingLine.text = if (duration != null) {
            activity.getString(R.string.paused_at, formatTime(exo.currentPosition), formatTime(duration))
        } else {
            activity.getString(R.string.toast_paused)
        }
        // Without art the frozen frame shows through, dimmed, so the screen never goes blank.
        loadingCard.setBackgroundColor(ContextCompat.getColor(activity, R.color.pause_veil))
        loadingCard.animate().cancel()
        loadingCard.alpha = 0f
        loadingCard.visibility = View.VISIBLE
        loadingCard.animate().alpha(1f).setDuration(PAUSE_FADE_MS).setInterpolator(easeOut).withLayer().start()
        // A slow drift keeps the art alive; transform only, and it stops with the screen.
        metaBackdrop.scaleX = 1f
        metaBackdrop.scaleY = 1f
        metaBackdrop.animate().scaleX(DRIFT_SCALE).scaleY(DRIFT_SCALE).setDuration(DRIFT_MS)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
        if (metaBackdrop.drawable != null) metaBackdrop.alpha = 1f
    }

    private fun schedulePauseScreen() {
        handler.removeCallbacks(showPauseScreen)
        val exo = player ?: return
        if (!loading && !exo.playWhenReady && exo.playbackState != Player.STATE_ENDED) {
            handler.postDelayed(showPauseScreen, PAUSE_SCREEN_DELAY_MS)
        }
    }

    private fun hidePauseScreen(animate: Boolean = true) {
        handler.removeCallbacks(showPauseScreen)
        if (!pauseScreenShown) return
        pauseScreenShown = false
        updateSkipPrompt()
        metaBackdrop.animate().cancel()
        metaBackdrop.scaleX = 1f
        metaBackdrop.scaleY = 1f
        metaClock.visibility = View.GONE
        loadingSource.visibility = View.VISIBLE
        loadingCard.animate().cancel()
        val reset = Runnable {
            loadingCard.visibility = View.GONE
            loadingCard.setBackgroundColor(ContextCompat.getColor(activity, R.color.loading_ground))
            showDetails()
        }
        if (animate) {
            loadingCard.animate().alpha(0f).setDuration(HIDE_MS).withLayer().withEndAction(reset).start()
        } else {
            reset.run()
        }
    }

    /**
     * Shows the loading card again, e.g. for a new stream. [waitForNewFrame] is false when the
     * same player is only re-preparing, which may not render a fresh first frame.
     */
    fun showLoading(line: String = activity.getString(R.string.loading_connecting), waitForNewFrame: Boolean = true) {
        loading = true
        updateSkipPrompt()
        errorShown = false
        errorActions.visibility = View.GONE
        if (waitForNewFrame) firstFrameRendered = false
        revealHeld = false
        handler.removeCallbacks(checkReveal)
        boothStartedAt = SystemClock.uptimeMillis()
        hidePauseScreen(animate = false)
        loadingCard.setBackgroundColor(ContextCompat.getColor(activity, R.color.loading_ground))
        loadingLine.text = line
        loadingLine.setTextColor(ContextCompat.getColor(activity, R.color.ink_dim))
        loadingSpinner.visibility = View.VISIBLE
        loadingSource.text = activity.getString(R.string.loading_source, sourceApp ?: activity.getString(R.string.another_app))
        loadingCard.animate().cancel()
        loadingCard.alpha = 1f
        loadingCard.visibility = View.VISIBLE
        preShow.begin(line)
        setBooth(true)
    }

    fun setLoadingLine(line: String) {
        loadingLine.text = line
        preShow.setStatus(line)
    }

    /** The frame rate the display was matched to, or left at, for the pre-show's log. */
    fun noteFrameRate(fps: Float) = preShow.setFrameRate(fps)

    /** The loading card shows the pre-show while a stream loads; errors and the pause screen use the meta block. */
    private fun setBooth(on: Boolean) {
        if (on) preShow.show() else preShow.hide()
        metaBlock.visibility = if (on) View.INVISIBLE else View.VISIBLE
        loadingSource.visibility = if (on) View.GONE else View.VISIBLE
        metaBackdrop.colorFilter = if (on) boothArt else null
        if (metaBackdrop.drawable != null) {
            metaBackdrop.animate().cancel()
            metaBackdrop.alpha = if (on) BOOTH_ART_ALPHA else 1f
        }
    }

    @Suppress("DEPRECATION")
    private fun displayInfo(): DisplayInfo {
        val display = activity.windowManager.defaultDisplay
        val mode = display.mode
        val hdr = display.hdrCapabilities?.supportedHdrTypes?.toList()?.mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DOLBY_VISION
                Display.HdrCapabilities.HDR_TYPE_HDR10, HDR_TYPE_HDR10_PLUS -> HdrType.HDR10
                Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.HLG
                else -> null
            }
        }?.toSet().orEmpty()
        return DisplayInfo(mode.physicalWidth, mode.physicalHeight, mode.refreshRate, hdr)
    }

    /** Errors stay on screen until Back, so they can be read; [code] is small print for bug reports. */
    fun showError(message: String, code: String) {
        hideControls()
        closePanel(showControlsAfter = false)
        showLoading(message)
        loadingSpinner.visibility = View.GONE
        loadingLine.setTextColor(ContextCompat.getColor(activity, R.color.ink))
        loadingSource.text = activity.getString(R.string.error_footer, code)
        errorShown = true
        setBooth(false)
        errorActions.visibility = View.VISIBLE
        retryButton.requestFocus()
    }

    // ---- Keys ----------------------------------------------------------------------------------

    /** A long press of OK was taken for something else; forget the press that started it. */
    fun cancelPendingPress() {
        centerPressedWhileHidden = false
        swallowReleaseOf = KeyEvent.KEYCODE_UNKNOWN
    }

    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (code != swallowReleaseOf) return false
            swallowReleaseOf = KeyEvent.KEYCODE_UNKNOWN
            if (isCenter(code) && centerPressedWhileHidden) {
                centerPressedWhileHidden = false
                if (skipPrompt.press()) return true
                togglePlayPause()
                showGlyph(if (player?.playWhenReady == true) R.drawable.ic_play else R.drawable.ic_pause)
                showControls(playButton)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        // Any press counts as someone watching; the pause screen waits for a quiet spell.
        if (!pauseScreenShown) schedulePauseScreen()

        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) onBack()
            return swallow(code)
        }
        // On the error card the remote moves between its buttons; while loading it does nothing.
        if (loading) return if (errorShown) false else swallow(code, isRemoteKey(code))

        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) {
                    when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> player?.playWhenReady = true
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.playWhenReady = false
                        else -> togglePlayPause()
                    }
                    if (panel == null) showControls(playButton)
                }
                return swallow(code)
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return swallow(code).also { skip(FORWARD_MS) }
            KeyEvent.KEYCODE_MEDIA_REWIND -> return swallow(code).also { skip(-BACK_MS) }
        }

        if (pauseScreenShown && isRemoteKey(code)) {
            if (event.repeatCount == 0) {
                // OK resumes straight from the pause screen; any other key brings back the controls.
                if (isCenter(code)) player?.playWhenReady = true
                hidePauseScreen()
                showControls(playButton)
            }
            return swallow(code)
        }

        return when {
            panel != null -> handlePanelKey(code)
            !controlsVisible -> handleHiddenKey(event)
            else -> handleVisibleKey(code)
        }
    }

    private fun handleHiddenKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        when {
            isCenter(code) -> if (event.repeatCount == 0) centerPressedWhileHidden = true
            code == KeyEvent.KEYCODE_DPAD_LEFT -> skip(-BACK_MS)
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> skip(FORWARD_MS)
            code == KeyEvent.KEYCODE_DPAD_UP -> showControls(playButton)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> openPanel(Panel.SUBTITLES, subtitlesButton)
            code == KeyEvent.KEYCODE_MENU -> openPanel(Panel.MORE, moreButton)
            else -> return false
        }
        return swallow(code)
    }

    private fun handleVisibleKey(code: Int): Boolean {
        scheduleHide()
        when (code) {
            KeyEvent.KEYCODE_MENU -> openPanel(Panel.MORE, moreButton)
            // Everything else is focus movement and clicks, which the views handle themselves.
            else -> return false
        }
        return swallow(code)
    }

    private fun handlePanelKey(code: Int): Boolean = when (code) {
        // On a stepped setting Left and Right change it (held, they keep going); elsewhere Left steps back out.
        KeyEvent.KEYCODE_DPAD_LEFT -> swallow(code).also {
            if (!stepFocused(-1)) if (page != null) closePage() else closePanel(showControlsAfter = true)
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> swallow(code).also { stepFocused(1) }
        KeyEvent.KEYCODE_MENU -> swallow(code)
        else -> false
    }

    /**
     * Back, from the key or from Android's back callback: closes whatever is on screen (a panel or
     * the controls); with nothing showing, or while loading, it returns to the source app.
     */
    fun onBack() {
        when {
            loading -> onExit()
            pauseScreenShown -> { hidePauseScreen(); showControls(playButton) }
            page != null -> closePage()
            panel != null -> closePanel(showControlsAfter = false)
            controlsVisible -> hideControls()
            SystemClock.uptimeMillis() - exitArmedAt < EXIT_CONFIRM_MS -> onExit()
            else -> {
                // A stray Back mid-film shouldn't drop you out; ask for a second press.
                exitArmedAt = SystemClock.uptimeMillis()
                showExitHint()
            }
        }
    }

    private fun swallow(code: Int, swallowIt: Boolean = true): Boolean {
        if (swallowIt) swallowReleaseOf = code
        return swallowIt
    }

    private fun isCenter(code: Int) =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isRemoteKey(code: Int) = isCenter(code) || code == KeyEvent.KEYCODE_BACK ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU

    // ---- Controls ------------------------------------------------------------------------------

    private fun showControls(focus: View) {
        if (!controlsVisible) {
            controlsVisible = true
            updatePlayButton()
            updateProgress()
            hideMiniBar()
            reveal(infoBar, fromY = -18f)
            reveal(controlBar, fromY = 28f)
            playerView.subtitleView?.setBottomPaddingFraction(subtitleLift())
            handler.post(progressTicker)
            updateSkipPrompt()
        }
        focus.requestFocus()
        scheduleHide()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(autoHide)
        conceal(infoBar, toY = -18f)
        conceal(controlBar, toY = 28f)
        playerView.subtitleView?.setBottomPaddingFraction(subtitleLift())
        updateSkipPrompt()
    }

    private fun reveal(view: View, fromY: Float) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.translationY = fromY * density
            view.visibility = View.VISIBLE
        }
        view.animate().alpha(1f).translationY(0f).setDuration(SHOW_MS).setInterpolator(easeOut).withLayer().start()
    }

    private fun conceal(view: View, toY: Float) {
        view.animate().cancel()
        view.animate().alpha(0f).translationY(toY * density).setDuration(HIDE_MS).setInterpolator(easeOut).withLayer()
            .withEndAction { view.visibility = View.INVISIBLE }
            .start()
    }

    /** Controls fade after a few seconds of no input, but only while playing. */
    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        if (player?.isPlaying == true) handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private val autoHide = Runnable { if (panel == null && player?.isPlaying == true) hideControls() }

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_MS)
        }
    }

    private fun updateProgress() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = exo.currentPosition
        seekBar.update(position, exo.bufferedPosition, duration)
        elapsed.text = formatTime(position)
        clock.text = DateFormat.getTimeFormat(activity).format(Date())
        if (duration > 0) {
            val left = ((duration - position) / exo.playbackParameters.speed).toLong()
            remaining.text = "−${formatTime(duration - position)}"
            endsAt.text = activity.getString(R.string.ends_at, DateFormat.getTimeFormat(activity).format(Date(System.currentTimeMillis() + left)))
        } else {
            remaining.text = ""
            endsAt.text = ""
        }
    }

    private fun updatePlayButton() {
        val playing = player?.playWhenReady == true && player?.playbackState != Player.STATE_ENDED
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = activity.getString(if (playing) R.string.control_pause else R.string.control_play)
        if (playButton.isFocused) focusLabel.text = playButton.contentDescription
    }

    // ---- Phone companion -----------------------------------------------------------------------

    override fun companionState(): JSONObject {
        val exo = player ?: return JSONObject().put("idle", true)
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        return JSONObject()
            .put("title", details?.name ?: title.text.toString())
            .put("kicker", details?.kicker ?: "")
            .put("positionMs", exo.currentPosition)
            .put("durationMs", duration)
            .put("playing", exo.playWhenReady)
    }

    override fun companionControl(action: String, ms: Long) {
        val exo = player ?: return
        if (loading) return
        when (action) {
            "play" -> exo.playWhenReady = true
            "pause" -> exo.playWhenReady = false
            "toggle" -> {
                togglePlayPause()
                showGlyph(if (exo.playWhenReady) R.drawable.ic_play else R.drawable.ic_pause)
            }
            "skip" -> {
                hidePauseScreen()
                skip(ms)
            }
            "seek" -> {
                hidePauseScreen()
                val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                exo.seekTo(ms.coerceIn(0L, duration))
                showToast(activity.getString(R.string.companion_jumped, formatTime(ms)), ADD_TOAST_MS)
                if (controlsVisible) updateProgress() else showMiniBar()
            }
        }
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.playWhenReady = !exo.playWhenReady
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    /** Skips with the remote while controls are hidden; quick repeats add up in one toast. */
    private fun skip(deltaMs: Long) {
        val now = SystemClock.uptimeMillis()
        skipTotalMs = if (now < skipShownUntil && (skipTotalMs > 0) == (deltaMs > 0)) skipTotalMs + deltaMs else deltaMs
        skipShownUntil = now + TOAST_MS
        seekBy(deltaMs)
        val seconds = abs(skipTotalMs) / 1000
        val amount = if (seconds < 60) "$seconds" else "${seconds / 60}:${"%02d".format(seconds % 60)}"
        showSkip(forward = skipTotalMs > 0, amount = amount)
        if (controlsVisible) updateProgress() else showMiniBar()
    }

    /** The skip glyph on the side that was pressed; the other side's glyph gives way at once. */
    private fun showSkip(forward: Boolean, amount: String) {
        val shown = if (forward) skipForward else skipBack
        val other = if (forward) skipBack else skipForward
        fitInRing(if (forward) skipForwardAmount else skipBackAmount, amount)
        other.animate().cancel()
        other.alpha = 0f
        other.visibility = View.INVISIBLE
        shown.animate().cancel()
        val nudge = (if (forward) 10f else -10f) * density
        if (shown.visibility != View.VISIBLE) {
            shown.alpha = 0f
            shown.translationX = -nudge
            shown.visibility = View.VISIBLE
        } else {
            // A repeat press bumps the glyph again.
            shown.translationX = -nudge / 2
        }
        shown.animate().alpha(1f).translationX(0f).setDuration(SKIP_IN_MS).setInterpolator(easeOut).start()
        handler.removeCallbacks(hideSkip)
        handler.postDelayed(hideSkip, TOAST_MS)
    }

    /**
     * Sets the skip time at the largest whole size that fits the box inside the ring, so "10" stays big and "34:00"
     * steps down rather than running over the stroke. Digits are tabular, so the size only changes with the length.
     */
    private fun fitInRing(view: TextView, amount: String) {
        val maxWidth = activity.resources.getDimension(R.dimen.skip_amount_width)
        var sp = SKIP_AMOUNT_MAX_SP
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        while (sp > SKIP_AMOUNT_MIN_SP && view.paint.measureText(amount) > maxWidth) {
            sp -= 1f
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
        view.text = amount
    }

    private val hideSkip = Runnable {
        for (glyph in listOf(skipBack, skipForward)) {
            if (glyph.visibility != View.VISIBLE) continue
            glyph.animate().alpha(0f).setDuration(HIDE_MS).withEndAction { glyph.visibility = View.INVISIBLE }.start()
        }
    }

    /** A slim progress line while skipping with the controls hidden. */
    private fun showMiniBar() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        miniSeek.update(exo.currentPosition, exo.bufferedPosition, duration)
        miniTime.text = formatTime(exo.currentPosition)
        miniBar.animate().cancel()
        if (miniBar.visibility != View.VISIBLE) {
            miniBar.alpha = 0f
            miniBar.visibility = View.VISIBLE
        }
        miniBar.animate().alpha(1f).setDuration(SKIP_IN_MS).start()
        handler.removeCallbacks(hideMiniBarLater)
        handler.postDelayed(hideMiniBarLater, MINI_BAR_MS)
    }

    private val hideMiniBarLater = Runnable { hideMiniBar() }

    private fun hideMiniBar() {
        handler.removeCallbacks(hideMiniBarLater)
        if (miniBar.visibility != View.VISIBLE) return
        miniBar.animate().cancel()
        miniBar.animate().alpha(0f).setDuration(HIDE_MS).withEndAction { miniBar.visibility = View.INVISIBLE }.start()
    }

    /** Play or pause with the controls hidden: the new state's symbol pops in the middle and fades. */
    private fun showGlyph(icon: Int) {
        centerGlyph.setImageResource(icon)
        centerGlyph.animate().cancel()
        centerGlyph.alpha = 0f
        centerGlyph.scaleX = 0.8f
        centerGlyph.scaleY = 0.8f
        centerGlyph.visibility = View.VISIBLE
        // The view's animator keeps its settings between runs, so each run sets its own delay.
        centerGlyph.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(SKIP_IN_MS).setInterpolator(easeOut)
            .withEndAction {
                centerGlyph.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(GLYPH_HOLD_MS).setDuration(HIDE_MS)
                    .withEndAction { centerGlyph.visibility = View.INVISIBLE }
                    .start()
            }
            .start()
    }

    /** Slides the one label under the focused round button; [button] null (the seek bar) hides it. */
    private fun labelFocused(button: View?) {
        focusLabel.animate().cancel()
        if (button == null) {
            focusLabel.animate().alpha(0f).setDuration(LABEL_MS).start()
            return
        }
        focusLabel.text = button.contentDescription
        focusLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val row = button.parent as View
        val centre = row.left + button.left + button.width / 2f - (focusLabel.parent as View).left
        val target = (centre - focusLabel.measuredWidth / 2f).coerceAtLeast(0f)
        if (focusLabel.alpha == 0f) {
            focusLabel.translationX = target
            focusLabel.animate().alpha(1f).setDuration(LABEL_MS).start()
        } else {
            focusLabel.animate().alpha(1f).translationX(target).setDuration(LABEL_MS).setInterpolator(easeOut).start()
        }
    }

    private fun setSpeed(speed: Float) {
        speedIndex = SPEEDS.indexOfFirst { it == speed }.coerceAtLeast(0)
        fastStalls = 0
        player?.setPlaybackSpeed(SPEEDS[speedIndex])
    }

    /**
     * A stream that stalls again and again at 2× is coming in slower than it plays: no buffer fixes
     * that, so after the second stall the viewer is told, once for each speed they pick.
     */
    private fun noteStall() {
        val speed = SPEEDS[speedIndex]
        if (loading || speed <= 1f || player?.playWhenReady != true) return
        if (SystemClock.uptimeMillis() - lastSeekAtMs < SEEK_SETTLE_MS) return
        fastStalls++
        if (fastStalls == STALLS_BEFORE_HINT) showToast(activity.getString(R.string.speed_cant_keep_up, speedName(speed)), STALL_TOAST_MS)
    }

    private fun speedLabel(): String = speedName(SPEEDS[speedIndex])

    private fun speedName(speed: Float): String =
        if (speed == 1f) "1×" else "${speed.toString().trimEnd('0').trimEnd('.')}×"

    private fun showToast(text: String, durationMs: Long = TOAST_MS) {
        toast.text = text
        toast.animate().cancel()
        if (toast.visibility != View.VISIBLE) {
            toast.alpha = 0f
            toast.scaleX = 0.92f
            toast.scaleY = 0.92f
            toast.visibility = View.VISIBLE
        }
        toast.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(easeOut).start()
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, durationMs)
    }

    /** "Back again to exit": a quiet line in the corner, so the film stays the only thing on screen. */
    private fun showExitHint() {
        exitHint.animate().cancel()
        if (exitHint.visibility != View.VISIBLE) {
            exitHint.alpha = 0f
            exitHint.visibility = View.VISIBLE
        }
        exitHint.animate().alpha(1f).setDuration(LABEL_MS).start()
        handler.removeCallbacks(hideExitHint)
        handler.postDelayed(hideExitHint, EXIT_CONFIRM_MS)
    }

    private val hideExitHint = Runnable {
        exitHint.animate().alpha(0f).setDuration(HIDE_MS).withEndAction { exitHint.visibility = View.INVISIBLE }.start()
    }

    private val hideToast = Runnable {
        toast.animate().alpha(0f).setDuration(220).withEndAction { toast.visibility = View.INVISIBLE }.start()
    }

    /**
     * While held, the loading card stays up even once frames play, so the player can measure the
     * frame rate and switch the display out of sight.
     */
    var holdLoading = false
        set(value) {
            field = value
            if (!value && loading && firstFrameRendered && player?.isPlaying == true) hideLoading()
        }

    private fun hideLoading() {
        if (!loading || holdLoading || revealHeld) return
        if (preShow.isShowing && !canReveal()) {
            holdReveal()
            return
        }
        loading = false
        updateSkipPrompt()
        // Measuring the frame rate borrows the speed; give the viewer's choice back.
        player?.let { if (it.playbackParameters.speed != SPEEDS[speedIndex]) it.setPlaybackSpeed(SPEEDS[speedIndex]) }
        if (!preShow.isShowing) {
            loadingCard.animate().alpha(0f).setDuration(320).withLayer()
                .withEndAction { loadingCard.visibility = View.GONE }
                .start()
            showControls(playButton)
            return
        }
        // The film is already playing; the cue mark flashes over its first half second, then it cuts in.
        preShow.reveal {
            if (loading) return@reveal
            loadingCard.animate().alpha(0f).setDuration(CUT_MS)
                .withEndAction {
                    loadingCard.visibility = View.GONE
                    setBooth(false)
                }
                .start()
            showControls(playButton)
        }
    }

    // ---- Panels --------------------------------------------------------------------------------

    private fun openPanel(which: Panel, opener: View) {
        panelOpener = opener
        panel = which
        page = null
        updateSkipPrompt()
        handler.removeCallbacks(autoHide)
        hideControls()
        setPanelHeading()
        buildPanel(focusIndex = -1)

        sidePanel.animate().cancel()
        if (sidePanel.visibility != View.VISIBLE) {
            sidePanel.translationX = sidePanel.width.takeIf { it > 0 }?.toFloat() ?: (470 * density)
            sidePanel.alpha = 0f
            sidePanel.visibility = View.VISIBLE
        }
        sidePanel.animate().translationX(0f).alpha(1f).setDuration(PANEL_MS).setInterpolator(easeOut).withLayer().start()
    }

    private fun setPanelHeading() {
        val which = panel ?: return
        val current = page
        if (current == null) {
            panelKicker.visibility = View.GONE
            panelTitle.setText(titleFor(which))
        } else {
            panelKicker.text = activity.getString(titleFor(which)).uppercase()
            panelKicker.visibility = View.VISIBLE
            panelTitle.text = current.title
        }
    }

    private fun openPage(next: Page, fromIndex: Int) {
        page = next
        pageOpenerIndex = fromIndex
        setPanelHeading()
        buildPanel(focusIndex = -1)
    }

    private fun closePage() {
        page = null
        setPanelHeading()
        buildPanel(focusIndex = pageOpenerIndex)
    }

    private fun closePanel(showControlsAfter: Boolean) {
        if (panel == null) return
        panel = null
        page = null
        updateSkipPrompt()
        sidePanel.animate().cancel()
        sidePanel.animate().translationX(sidePanel.width.toFloat()).alpha(0f).setDuration(HIDE_MS).setInterpolator(easeOut)
            .withLayer()
            .withEndAction { sidePanel.visibility = View.INVISIBLE }
            .start()
        if (showControlsAfter) showControls(panelOpener ?: playButton)
    }

    /** Fills the open panel; [focusIndex] -1 focuses the checked row, or the first. */
    private fun buildPanel(focusIndex: Int) {
        val which = panel ?: return
        val inPage = page
        val items = inPage?.items?.invoke() ?: itemsFor(which)
        panelItems.removeAllViews()
        panelHint.text = null
        val inflater = LayoutInflater.from(activity)
        val rows = items.mapIndexed { index, item ->
            item.section?.let { panelItems.addView(sectionHeading(it, first = index == 0)) }
            val row = inflater.inflate(R.layout.item_menu_row, panelItems, false)
            row.findViewById<TextView>(R.id.item_label).typeface = fonts.semibold
            row.findViewById<TextView>(R.id.item_value).typeface = fonts.semibold
            bindRow(row, item)
            row.id = View.generateViewId()
            row.setTag(R.id.item_label, index)
            row.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) panelHint.text = (v.tag as Item).hint }
            row.setOnClickListener {
                val current = row.tag as Item
                val opens = current.opens
                val adjust = current.adjust
                when {
                    opens != null -> openPage(opens, fromIndex = index)
                    // OK runs through a short set of choices; on a long range it means done.
                    adjust != null -> if (adjust.count <= CYCLE_MAX) stepFocused(1, wrap = true) else closePanel(showControlsAfter = true)
                    inPage != null -> { current.action(); closePage() }
                    current.keepOpen -> { current.action(); refreshRow(row) }
                    else -> { current.action(); closePanel(showControlsAfter = true) }
                }
            }
            (row.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (2 * density).toInt()
            panelItems.addView(row)
            row
        }
        // Keep focus inside the panel at both ends of the list.
        rows.firstOrNull()?.let { it.nextFocusUpId = it.id }
        rows.lastOrNull()?.let { it.nextFocusDownId = it.id }
        rows.forEach { it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }

        val target = when {
            focusIndex in rows.indices -> focusIndex
            else -> items.indexOfFirst { it.checked && !it.switch }.takeIf { it >= 0 } ?: 0
        }
        rows.getOrNull(target)?.let {
            it.requestFocus()
            // Land already highlighted instead of fading into place on every rebuild.
            it.jumpDrawablesToCurrentState()
            panelHint.text = (it.tag as Item).hint
            scrollRowToTop(it)
        }
    }

    private fun focusedIndex(): Int = panelItems.focusedChild?.getTag(R.id.item_label) as? Int ?: -1

    /** Redraws one row from fresh items; rebuilding the list would make the focused row jump. */
    private fun refreshRow(row: View) {
        val which = panel ?: return
        val index = row.getTag(R.id.item_label) as? Int ?: return
        val items = page?.items?.invoke() ?: itemsFor(which)
        val item = items.getOrNull(index) ?: return
        bindRow(row, item)
        if (row.hasFocus()) panelHint.text = item.hint
    }

    /** Left or Right on a stepped setting. False when the focused row isn't one, so Left can step out instead. */
    private fun stepFocused(delta: Int, wrap: Boolean = false): Boolean {
        val row = panelItems.focusedChild ?: return false
        val adjust = (row.tag as? Item)?.adjust ?: return false
        val next = if (wrap) (adjust.index + delta).mod(adjust.count) else (adjust.index + delta).coerceIn(0, adjust.count - 1)
        if (next != adjust.index) {
            adjust.set(next)
            refreshRow(row)
        }
        return true
    }

    private fun sectionHeading(text: String, first: Boolean) = TextView(activity).apply {
        this.text = text.uppercase()
        typeface = fonts.semibold
        textSize = 12f
        letterSpacing = 0.12f
        setTextColor(activity.getColor(R.color.ink_secondary))
        val side = (16 * density).toInt()
        setPadding(side, ((if (first) 2 else 18) * density).toInt(), side, (6 * density).toInt())
    }

    private fun bindRow(row: View, item: Item) {
        row.tag = item
        row.findViewById<TextView>(R.id.item_label).text = item.label
        val value = row.findViewById<TextView>(R.id.item_value)
        value.text = item.value
        value.visibility = if (item.value.isNullOrEmpty()) View.GONE else View.VISIBLE
        val adjust = item.adjust
        val less = row.findViewById<ImageView>(R.id.item_less)
        val more = row.findViewById<ImageView>(R.id.item_more)
        less.visibility = if (adjust != null) View.VISIBLE else View.GONE
        more.visibility = if (adjust != null || item.opens != null) View.VISIBLE else View.GONE
        // Stepping arrows show only on the focused row; the arrow into a list always shows.
        more.imageTintList = activity.getColorStateList(if (adjust != null) R.color.row_arrow else R.color.row_detail)
        // At either end of a range that arrow fades: there is nowhere further to go.
        less.alpha = if (adjust != null && adjust.index == 0) END_ARROW_ALPHA else 1f
        more.alpha = if (adjust != null && adjust.index == adjust.count - 1) END_ARROW_ALPHA else 1f
        val switch = row.findViewById<SwitchMark>(R.id.item_switch)
        switch.visibility = if (item.switch) View.VISIBLE else View.GONE
        switch.on = item.checked
        // Picks keep the check's space when unchecked, so their formats line up.
        row.findViewById<ImageView>(R.id.item_check).visibility = when {
            item.checked && !item.switch -> View.VISIBLE
            item.switch || adjust != null || item.opens != null -> View.GONE
            else -> View.INVISIBLE
        }
    }

    private fun itemsFor(which: Panel): List<Item> = when (which) {
        Panel.AUDIO -> audioItems()
        Panel.SUBTITLES -> subtitleItems()
        Panel.PICTURE -> pictureItems()
        Panel.MORE -> moreItems()
    }

    private fun audioItems(): List<Item> {
        val exo = player ?: return emptyList()
        val items = mutableListOf<Item>()
        var number = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                number++
                if (!group.isTrackSupported(i, /* allowExceedsCapabilities = */ true)) continue
                val format = group.getTrackFormat(i)
                items += Item(
                    label = uniqueName(FormatNames.language(format) ?: activity.getString(R.string.track_unknown, number), items),
                    value = FormatNames.audioDetail(format),
                    checked = group.isTrackSelected(i),
                    section = activity.getString(R.string.menu_track).takeIf { items.isEmpty() },
                ) { selectTrack(group, i) }
            }
        }
        val delays = PlayerPrefs.AUDIO_DELAYS_MS
        items += Item(
            activity.getString(R.string.audio_sync),
            AudioSyncNames.short(activity, prefs.audioDelayMs),
            checked = false,
            adjust = Adjust(indexIn(delays, prefs.audioDelayMs), delays.size) { i ->
                prefs.audioDelayMs = delays[i]
                onAudioDelayChanged?.invoke(delays[i])
            },
            hint = AudioSyncNames.hint(activity, prefs.audioDelayMs) ?: activity.getString(R.string.audio_sync_none_hint),
            section = activity.getString(R.string.menu_sound),
        )
        items += Item(
            activity.getString(R.string.night_mode),
            value = null,
            checked = prefs.nightMode,
            keepOpen = true,
            switch = true,
            hint = activity.getString(R.string.night_mode_detail),
        ) {
            prefs.nightMode = !prefs.nightMode
            enhancement.nightMode = prefs.nightMode
            if (prefs.nightMode) needDecodedAudio()
        }
        items += Item(
            activity.getString(R.string.dialogue_boost),
            value = null,
            checked = prefs.dialogueBoost,
            keepOpen = true,
            switch = true,
            hint = activity.getString(R.string.dialogue_boost_detail),
        ) {
            prefs.dialogueBoost = !prefs.dialogueBoost
            enhancement.dialogueBoost = prefs.dialogueBoost
            if (prefs.dialogueBoost) needDecodedAudio()
        }
        return items
    }

    /** Sound shaping only works on decoded audio; passthrough has to give way first. */
    private fun needDecodedAudio() {
        if (stats.isPassthrough) onNeedsDecodedAudio?.invoke()
    }

    private fun subtitleItems(): List<Item> {
        val exo = player ?: return emptyList()
        val textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        var anySelected = false
        val tracks = mutableListOf<Item>()
        val forcedTracks = mutableListOf<Item>()
        var number = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                number++
                if (!group.isTrackSupported(i, true)) continue
                val format = group.getTrackFormat(i)
                val selected = !textDisabled && group.isTrackSelected(i)
                anySelected = anySelected || selected
                // Forced tracks go below the full ones: picked by mistake they look like broken subtitles.
                val forced = FormatNames.isForced(format)
                val name = FormatNames.language(format) ?: activity.getString(R.string.subs_unknown, number)
                val item = Item(
                    label = uniqueName(name, tracks + forcedTracks),
                    value = FormatNames.textCodec(format).takeIf { it != "Unknown" },
                    checked = selected,
                    hint = if (forced) activity.getString(R.string.subs_forced_hint) else null,
                ) { selectTrack(group, i) }
                if (forced) forcedTracks += item else tracks += item
            }
        }
        tracks += forcedTracks
        val delays = PlayerPrefs.SUBTITLE_DELAYS_MS
        val style = PlayerPrefs.SUBTITLE_STYLES[prefs.subtitleStyle]
        val position = PlayerPrefs.SUBTITLE_POSITIONS[prefs.subtitlePosition]
        return buildList {
            add(Item(
                activity.getString(R.string.subs_off),
                value = null,
                checked = !anySelected,
                hint = activity.getString(R.string.subs_off_detail),
                section = activity.getString(R.string.menu_track),
            ) {
                player?.let {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            })
            addAll(tracks)
            add(findOnlineItem())
            add(Item(
                activity.getString(R.string.menu_timing),
                subtitleTimingLabel(subtitleDelayMs),
                checked = false,
                adjust = Adjust(indexIn(delays, subtitleDelayMs), delays.size) { setSubtitleDelay(delays[it]) },
                hint = subtitleTimingHint(subtitleDelayMs) ?: activity.getString(R.string.subs_timing_none_hint),
                section = activity.getString(R.string.menu_look),
            ))
            add(Item(
                activity.getString(R.string.menu_style),
                style.name,
                checked = false,
                adjust = Adjust(prefs.subtitleStyle, PlayerPrefs.SUBTITLE_STYLES.size) {
                    prefs.subtitleStyle = it
                    applySubtitleStyle()
                },
                hint = style.detail,
            ))
            add(Item(
                activity.getString(R.string.menu_size),
                PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].name,
                checked = false,
                adjust = Adjust(prefs.subtitleSize, PlayerPrefs.SUBTITLE_SIZES.size) {
                    prefs.subtitleSize = it
                    applySubtitleStyle()
                },
                hint = activity.getString(R.string.menu_size_hint),
            ))
            add(Item(
                activity.getString(R.string.menu_position),
                position.name,
                checked = false,
                adjust = Adjust(prefs.subtitlePosition, PlayerPrefs.SUBTITLE_POSITIONS.size) {
                    prefs.subtitlePosition = it
                    applySubtitleStyle()
                },
                hint = position.detail,
            ))
        }
    }

    /** "Find online": a list of OpenSubtitles results once the search is back. */
    private fun findOnlineItem(): Item {
        val (value, hint) = when (onlineSearch) {
            OnlineSearch.UNMATCHED -> R.string.menu_find_unmatched to activity.getString(R.string.subs_find_unmatched)
            OnlineSearch.SEARCHING -> R.string.menu_find_searching to activity.getString(R.string.subs_find_searching)
            OnlineSearch.DONE -> if (onlineSubtitles.isEmpty()) {
                R.string.menu_find_none to activity.getString(R.string.subs_find_none)
            } else {
                R.string.menu_find_count to activity.getString(R.string.subs_find_count, onlineSubtitles.size)
            }
        }
        val results = onlineResults()
        return Item(
            activity.getString(R.string.menu_find),
            activity.getString(value, onlineSubtitles.size),
            checked = false,
            // With nothing to list, the row stays put instead of opening an empty page.
            keepOpen = results.isEmpty(),
            opens = if (results.isEmpty()) null else Page(activity.getString(R.string.subs_find_title)) { results },
            hint = hint,
        )
    }

    /** The viewer's subtitle language first, then English, each numbered in the database's order. */
    private fun onlineResults(): List<Item> {
        val preferred = listOf(prefs.subtitleLanguage.ifEmpty { Locale.getDefault().language }, "en")
        val counts = HashMap<String?, Int>()
        return SubtitleLanguages.rank(onlineSubtitles, preferred, MAX_ONLINE_RESULTS).map { sub ->
            val n = (counts[sub.language] ?: 0) + 1
            counts[sub.language] = n
            val name = languageName(sub.language)
            val source = activity.getString(R.string.subs_find_source, n)
            Item(name, source, checked = false, hint = source) {
                showToast(activity.getString(R.string.subs_adding), ADD_TOAST_MS)
                // The phone's transcript follows the file the TV is now showing.
                PhoneCompanion.loadTranscript(listOf(sub.url))
                onAddSubtitle?.invoke(sub.url, sub.language, "$name · OpenSubtitles $n")
            }
        }
    }

    private fun languageName(code: String?): String {
        if (code.isNullOrEmpty()) return activity.getString(R.string.language_unknown)
        PlayerPrefs.LANGUAGES.firstOrNull { it.code == code }?.let { return it.name }
        return Locale(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }.ifEmpty { code }
    }

    // ---- Subtitle timing: per video, so it resets with the next one --------------------------------

    private val subtitleDelayMs: Int get() = (subtitleDelay.offsetUs / 1_000).toInt()

    private fun setSubtitleDelay(ms: Int) {
        // Applies from the next line on, with no seek or stall.
        subtitleDelay.offsetUs = ms * 1_000L
    }

    private fun subtitleTimingLabel(ms: Int): String {
        if (ms == 0) return activity.getString(R.string.menu_in_sync)
        val seconds = String.format(Locale.US, "%.2f", abs(ms) / 1000f).trimEnd('0').trimEnd('.')
        return activity.getString(if (ms > 0) R.string.subs_timing_later else R.string.subs_timing_earlier, seconds)
    }

    private fun subtitleTimingHint(ms: Int): String? = when {
        ms > 0 -> activity.getString(R.string.subs_timing_later_hint)
        ms < 0 -> activity.getString(R.string.subs_timing_earlier_hint)
        else -> null
    }

    /** Where [value] sits in a stepped setting's [values], or the nearest step to it. */
    private fun indexIn(values: List<Int>, value: Int): Int =
        values.indexOf(value).takeIf { it >= 0 } ?: values.indices.minBy { abs(values[it] - value) }

    private fun pictureItems(): List<Item> {
        fun mode(label: Int, detail: Int, mode: Int, first: Boolean = false) = Item(
            activity.getString(label),
            value = null,
            checked = prefs.resizeMode == mode,
            hint = activity.getString(detail),
            section = activity.getString(R.string.menu_shape).takeIf { first },
        ) {
            prefs.resizeMode = mode
            playerView.resizeMode = mode
        }
        return listOf(
            mode(R.string.picture_fit, R.string.picture_fit_detail, AspectRatioFrameLayout.RESIZE_MODE_FIT, first = true),
            mode(R.string.picture_fill, R.string.picture_fill_detail, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            mode(R.string.picture_stretch, R.string.picture_stretch_detail, AspectRatioFrameLayout.RESIZE_MODE_FILL),
            Item(
                activity.getString(R.string.picture_match),
                value = null,
                checked = prefs.matchFrameRate,
                keepOpen = true,
                switch = true,
                hint = activity.getString(if (prefs.matchFrameRate) R.string.picture_match_on else R.string.picture_match_off),
                section = activity.getString(R.string.menu_display),
            ) { prefs.matchFrameRate = !prefs.matchFrameRate },
        )
    }

    private fun moreItems(): List<Item> = listOf(
        Item(
            activity.getString(R.string.companion_title),
            PhoneCompanion.address()?.let { activity.getString(R.string.companion_value, it, PhoneCompanion.code) }
                ?: activity.getString(R.string.companion_offline_value),
            checked = false,
            hint = activity.getString(R.string.pair_hint),
        ) { PairingCard.show(activity, fonts) },
        Item(
            activity.getString(R.string.more_stats),
            value = null,
            checked = stats.isVisible,
            keepOpen = true,
            switch = true,
            hint = activity.getString(R.string.menu_stats_hint),
        ) { stats.toggle() },
        Item(
            activity.getString(R.string.menu_speed),
            speedLabel(),
            checked = false,
            adjust = Adjust(SPEED_CHOICES.indexOf(SPEEDS[speedIndex]), SPEED_CHOICES.size) { setSpeed(SPEED_CHOICES[it]) },
            hint = activity.getString(R.string.menu_speed_hint),
        ),
    )

    /** Two tracks that clean up to the same name get numbered, so each row is still tellable apart. */
    private fun uniqueName(name: String, existing: List<Item>): String {
        if (existing.none { it.label == name }) return name
        var n = 2
        while (existing.any { it.label == "$name $n" }) n++
        return "$name $n"
    }

    private fun selectTrack(group: Tracks.Group, index: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun titleFor(which: Panel) = when (which) {
        Panel.AUDIO -> R.string.panel_audio
        Panel.SUBTITLES -> R.string.panel_subtitles
        Panel.PICTURE -> R.string.panel_picture
        Panel.MORE -> R.string.panel_more
    }

    // ---- Badges and subtitles ------------------------------------------------------------------

    private fun updateBadges(tracks: Tracks) {
        val video = selectedFormat(tracks, C.TRACK_TYPE_VIDEO)
        val audio = selectedFormat(tracks, C.TRACK_TYPE_AUDIO)
        val labels = buildList {
            if (video != null) {
                when {
                    video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> add("Dolby Vision")
                    video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> add("HDR10")
                    video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> add("HLG")
                }
            }
            when (audio?.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> add("Atmos")
                MimeTypes.AUDIO_TRUEHD -> add("TrueHD")
                MimeTypes.AUDIO_DTS_X -> add("DTS:X")
                MimeTypes.AUDIO_DTS_HD -> add("DTS-HD")
            }
            if (video != null && (video.height >= 2000 || video.width >= 3800)) add("4K")
        }
        badges.removeAllViews()
        labels.forEach { text ->
            badges.addView(TextView(activity).apply {
                this.text = text.uppercase()
                textSize = 11f
                typeface = fonts.bold
                letterSpacing = 0.08f
                setTextColor(ContextCompat.getColor(activity, R.color.ink))
                setBackgroundResource(R.drawable.badge_bg)
                val h = (7 * density).toInt()
                val v = (3 * density).toInt()
                setPadding(h, v, h, v)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * density).toInt()
            })
        }
    }

    private fun selectedFormat(tracks: Tracks, type: Int): Format? {
        for (group in tracks.groups) {
            if (group.type != type || !group.isSelected) continue
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
        return null
    }

    private fun applySubtitleStyle() {
        val view = playerView.subtitleView ?: return
        val style = PlayerPrefs.SUBTITLE_STYLES[prefs.subtitleStyle]
        view.setStyle(
            CaptionStyleCompat(
                style.text,
                style.background,
                0x00000000,
                style.edgeType,
                0xFF000000.toInt(),
                fonts.semibold,
            ),
        )
        view.setFractionalTextSize(PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].fraction)
        view.setBottomPaddingFraction(subtitleLift())
    }

    /** Subtitles sit at the viewer's chosen height, and clear of the controls while they show. */
    private fun subtitleLift(): Float {
        val rest = PlayerPrefs.SUBTITLE_POSITIONS[prefs.subtitlePosition].lift
        return if (controlsVisible) maxOf(rest, SUBTITLE_LIFT_CONTROLS) else rest
    }

    // ---- Player events -------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        // The loading card stays until the picture is moving, so a display mode switch happens behind it.
        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            if (player?.isPlaying == true) hideLoading()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Audio-only streams never render a frame.
            if (isPlaying && (firstFrameRendered || player?.videoFormat == null)) hideLoading()
            if (controlsVisible) scheduleHide()
            if (isPlaying) hidePauseScreen() else schedulePauseScreen()
            updatePlayButton()
        }

        override fun onPlaybackStateChanged(state: Int) {
            updatePlayButton()
            if (state == Player.STATE_BUFFERING) noteStall()
        }

        // Whatever subtitles are on screen also feed the phone's transcript when no file could be read.
        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString(" ")
            val position = player?.currentPosition ?: return
            if (text.isNotBlank()) PhoneCompanion.liveCue(position, text)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayButton()
            skipPrompt.refresh()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAtMs = SystemClock.uptimeMillis()
            skipPrompt.refresh()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!loading && SPEEDS[speedIndex] != 1f && playbackParameters.speed == 1f) {
                onSpeedNeedsDecodedAudio?.invoke(SPEEDS[speedIndex])
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateBadges(tracks)
            if (page == null && (panel == Panel.AUDIO || panel == Panel.SUBTITLES)) buildPanel(focusIndex = focusedIndex())
        }

    }

    private companion object {
        const val TRANSCRIPT_TRIES = 3
        val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        /** The same speeds, slowest first, as the speed list shows them. */
        val SPEED_CHOICES = SPEEDS.sorted()
        const val BACK_MS = 10_000L
        const val FORWARD_MS = 30_000L
        const val EXIT_CONFIRM_MS = 2_500L
        const val AUTO_HIDE_MS = 4_000L
        const val PROGRESS_MS = 500L
        const val TOAST_MS = 800L
        const val STALL_TOAST_MS = 3_500L
        const val STALLS_BEFORE_HINT = 2
        const val SEEK_SETTLE_MS = 1_500L
        const val TMDB_PHOTOS = "https://image.tmdb.org/t/p/w185"
        const val SHOW_MS = 240L
        const val HIDE_MS = 220L
        const val PANEL_MS = 320L
        const val SKIP_IN_MS = 160L
        const val SKIP_AMOUNT_MAX_SP = 22f
        const val SKIP_AMOUNT_MIN_SP = 12f
        const val GLYPH_HOLD_MS = 280L
        const val LABEL_MS = 160L
        const val MINI_BAR_MS = 1_600L
        const val ART_FADE_MS = 600L
        const val ART_QUICK_FADE_MS = 200L
        const val ART_WAIT_MS = 1_500L
        const val MIN_BOOTH_MS = 3_500L
        const val REVEAL_CHECK_MS = 100L
        const val ART_LOGO_BEAT_MS = 900L
        const val ART_QUICK_WINDOW_MS = 400L
        const val CUT_MS = 90L
        const val BOOTH_ART_ALPHA = 0.16f
        // Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS, which needs API 29 by name.
        const val HDR_TYPE_HDR10_PLUS = 4
        const val PAUSE_FADE_MS = 700L
        const val PAUSE_SCREEN_DELAY_MS = 8_000L
        const val DRIFT_MS = 40_000L
        const val DRIFT_SCALE = 1.06f
        const val SUBTITLE_LIFT_CONTROLS = 0.26f
        const val MAX_ONLINE_RESULTS = 40
        const val ADD_TOAST_MS = 1_500L
        /** A stepped setting with this many choices or fewer also steps round on OK. */
        const val CYCLE_MAX = 6
        const val END_ARROW_ALPHA = 0.3f

        fun formatTime(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
