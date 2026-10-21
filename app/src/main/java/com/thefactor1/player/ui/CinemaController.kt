package com.thefactor1.player.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.thefactor1.player.R
import com.thefactor1.player.playback.PlayerPrefs
import com.thefactor1.player.playback.StatsOverlay
import java.util.Date
import kotlin.math.abs

/**
 * The Cinema look: edge-to-edge scrims, a remote-first control bar and side panels for tracks
 * and picture. Nothing is drawn over the video unless asked for, and nothing updates while hidden.
 *
 * Remote, with controls hidden: OK pauses, Left/Right skip 10 s back / 30 s forward, Up shows the
 * controls, Down opens subtitles, Back returns to the source app, Menu opens More. Back with
 * controls or a panel showing closes them.
 */
@OptIn(UnstableApi::class)
class CinemaController(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val prefs: PlayerPrefs,
    private val stats: StatsOverlay,
    private val onExit: () -> Unit,
) {
    private enum class Panel { AUDIO, SUBTITLES, PICTURE, SPEED, MORE }

    private class Item(
        val label: String,
        val detail: String?,
        val checked: Boolean,
        /** Toggles keep the panel open and update in place; picking a track or a mode closes it. */
        val keepOpen: Boolean = false,
        /** A setting with several choices opens them as a list instead of cycling on each click. */
        val opens: Page? = null,
        val action: () -> Unit = {},
    )

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
    private val title: TextView = activity.findViewById(R.id.title)
    private val source: TextView = activity.findViewById(R.id.source)
    private val badges: LinearLayout = activity.findViewById(R.id.badges)
    private val clock: TextView = activity.findViewById(R.id.clock)
    private val elapsed: TextView = activity.findViewById(R.id.elapsed)
    private val remaining: TextView = activity.findViewById(R.id.remaining)
    private val seekBar: SeekBarView = activity.findViewById(R.id.seek_bar)
    private val playButton: TextView = activity.findViewById(R.id.btn_play)
    private val audioButton: TextView = activity.findViewById(R.id.btn_audio)
    private val subtitlesButton: TextView = activity.findViewById(R.id.btn_subtitles)
    private val speedButton: TextView = activity.findViewById(R.id.btn_speed)
    private val pictureButton: TextView = activity.findViewById(R.id.btn_picture)
    private val moreButton: TextView = activity.findViewById(R.id.btn_more)
    private val toast: TextView = activity.findViewById(R.id.center_toast)
    private val loadingCard: View = activity.findViewById(R.id.loading_card)
    private val loadingTitle: TextView = activity.findViewById(R.id.loading_title)
    private val loadingLine: TextView = activity.findViewById(R.id.loading_line)
    private val loadingSource: TextView = activity.findViewById(R.id.loading_source)
    private val loadingSpinner: View = activity.findViewById(R.id.loading_spinner)
    private val errorActions: View = activity.findViewById(R.id.error_actions)
    private val retryButton: TextView = activity.findViewById(R.id.btn_retry)
    private val otherPlayerButton: TextView = activity.findViewById(R.id.btn_other_player)

    /** The error card's two ways out. */
    var onRetry: (() -> Unit)? = null
    var onOpenElsewhere: (() -> Unit)? = null
    /** A default audio or subtitle choice changed; reapply it to the playing stream. */
    var onTrackDefaultsChanged: (() -> Unit)? = null
    /** Passthrough was switched on or off, which needs a new audio output. */
    var onAudioOutputChanged: (() -> Unit)? = null

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

    // Keys handled on press have their release swallowed too, so nothing behind them reacts.
    private var swallowReleaseOf = KeyEvent.KEYCODE_UNKNOWN
    private var centerPressedWhileHidden = false
    private var skipTotalMs = 0L
    private var skipShownUntil = 0L
    private var speedIndex = 0

    init {
        fonts.apply(fonts.bold, title, panelTitle, loadingTitle)
        fonts.apply(fonts.semibold, clock, elapsed, remaining, panelKicker, toast, playButton, audioButton,
            subtitlesButton, speedButton, pictureButton, moreButton)
        fonts.apply(fonts.medium, source, loadingLine, loadingSource)
        fonts.apply(fonts.semibold, retryButton, otherPlayerButton)
        retryButton.setOnClickListener { onRetry?.invoke() }
        otherPlayerButton.setOnClickListener { onOpenElsewhere?.invoke() }

        playButton.setOnClickListener { togglePlayPause(); scheduleHide() }
        audioButton.setOnClickListener { openPanel(Panel.AUDIO, it) }
        subtitlesButton.setOnClickListener { openPanel(Panel.SUBTITLES, it) }
        speedButton.setOnClickListener { openPanel(Panel.SPEED, it) }
        pictureButton.setOnClickListener { openPanel(Panel.PICTURE, it) }
        moreButton.setOnClickListener { openPanel(Panel.MORE, it) }
        seekBar.onScrub = { delta ->
            seekBy(delta)
            updateProgress()
            scheduleHide()
        }

        playerView.resizeMode = prefs.resizeMode
        applySubtitleStyle()
    }

    /** [detail] is the episode or year shown under the title, e.g. "S1 E1". */
    fun attach(exo: ExoPlayer, titleText: String?, detail: String?, sourceApp: String?) {
        player = exo
        exo.addListener(listener)
        this.sourceApp = sourceApp
        title.text = titleText.orEmpty()
        loadingTitle.text = listOfNotNull(titleText, detail).joinToString("  ·  ")
        val sourceLine = listOfNotNull(detail, sourceApp?.let { activity.getString(R.string.source_from, it) }).joinToString("  ·  ")
        source.text = sourceLine
        source.visibility = if (sourceLine.isEmpty()) View.GONE else View.VISIBLE
        loadingSource.text = activity.getString(R.string.loading_source, sourceApp ?: activity.getString(R.string.another_app))
        exo.setPlaybackSpeed(SPEEDS[speedIndex])
        updatePlayButton()
        updateBadges(exo.currentTracks)
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Shows the loading card again, e.g. for a new stream. [waitForNewFrame] is false when the
     * same player is only re-preparing, which may not render a fresh first frame.
     */
    fun showLoading(line: String = activity.getString(R.string.loading_connecting), waitForNewFrame: Boolean = true) {
        loading = true
        errorShown = false
        errorActions.visibility = View.GONE
        if (waitForNewFrame) firstFrameRendered = false
        loadingLine.text = line
        loadingLine.setTextColor(ContextCompat.getColor(activity, R.color.ink_secondary))
        loadingSpinner.visibility = View.VISIBLE
        loadingSource.text = activity.getString(R.string.loading_source, sourceApp ?: activity.getString(R.string.another_app))
        loadingCard.animate().cancel()
        loadingCard.alpha = 1f
        loadingCard.visibility = View.VISIBLE
    }

    fun setLoadingLine(line: String) {
        loadingLine.text = line
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
                togglePlayPause()
                showToast(activity.getString(if (player?.playWhenReady == true) R.string.toast_playing else R.string.toast_paused))
                showControls(playButton)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

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
        KeyEvent.KEYCODE_DPAD_LEFT -> swallow(code).also { if (page != null) closePage() else closePanel(showControlsAfter = true) }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> swallow(code)
        else -> false
    }

    /**
     * Back, from the key or from Android's back callback: closes whatever is on screen (a panel or
     * the controls); with nothing showing, or while loading, it returns to the source app.
     */
    fun onBack() {
        when {
            loading -> onExit()
            page != null -> closePage()
            panel != null -> closePanel(showControlsAfter = false)
            controlsVisible -> hideControls()
            SystemClock.uptimeMillis() - exitArmedAt < EXIT_CONFIRM_MS -> onExit()
            else -> {
                // A stray Back mid-film shouldn't drop you out; ask for a second press.
                exitArmedAt = SystemClock.uptimeMillis()
                showToast(activity.getString(R.string.toast_back_to_exit), EXIT_CONFIRM_MS)
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
            reveal(infoBar, fromY = -18f)
            reveal(controlBar, fromY = 28f)
            playerView.subtitleView?.setBottomPaddingFraction(SUBTITLE_LIFT_CONTROLS)
            handler.post(progressTicker)
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
        playerView.subtitleView?.setBottomPaddingFraction(SUBTITLE_LIFT_REST)
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
        remaining.text = if (duration > 0) {
            val left = ((duration - position) / exo.playbackParameters.speed).toLong()
            val endsAt = DateFormat.getTimeFormat(activity).format(Date(System.currentTimeMillis() + left))
            "−${formatTime(duration - position)}  ·  ${activity.getString(R.string.ends_at, endsAt)}"
        } else {
            ""
        }
    }

    private fun updatePlayButton() {
        val playing = player?.playWhenReady == true && player?.playbackState != Player.STATE_ENDED
        playButton.setText(if (playing) R.string.control_pause else R.string.control_play)
        playButton.setCompoundDrawablesRelativeWithIntrinsicBounds(if (playing) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0)
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
        val sign = if (skipTotalMs < 0) "−" else "+"
        val seconds = abs(skipTotalMs) / 1000
        showToast(if (seconds < 60) "$sign $seconds s" else "$sign ${seconds / 60}:${"%02d".format(seconds % 60)}")
        if (controlsVisible) updateProgress()
    }

    private fun setSpeed(speed: Float) {
        speedIndex = SPEEDS.indexOfFirst { it == speed }.coerceAtLeast(0)
        player?.setPlaybackSpeed(SPEEDS[speedIndex])
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
        if (!loading || holdLoading) return
        loading = false
        loadingCard.animate().alpha(0f).setDuration(320).withLayer()
            .withEndAction { loadingCard.visibility = View.GONE }
            .start()
        showControls(playButton)
    }

    // ---- Panels --------------------------------------------------------------------------------

    private fun openPanel(which: Panel, opener: View) {
        panelOpener = opener
        panel = which
        page = null
        handler.removeCallbacks(autoHide)
        hideControls()
        setPanelHeading()
        buildPanel(focusIndex = -1)

        sidePanel.animate().cancel()
        if (sidePanel.visibility != View.VISIBLE) {
            sidePanel.translationX = sidePanel.width.takeIf { it > 0 }?.toFloat() ?: (420 * density)
            sidePanel.alpha = 0f
            sidePanel.visibility = View.VISIBLE
        }
        sidePanel.animate().translationX(0f).alpha(1f).setDuration(PANEL_MS).setInterpolator(easeOut).withLayer().start()
    }

    private fun setPanelHeading() {
        val which = panel ?: return
        val current = page
        if (current == null) {
            panelKicker.text = activity.getString(kickerFor(which)).uppercase()
            panelTitle.setText(titleFor(which))
        } else {
            panelKicker.text = activity.getString(titleFor(which)).uppercase()
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
        val inflater = LayoutInflater.from(activity)
        val rows = items.mapIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_panel, panelItems, false)
            val label = row.findViewById<TextView>(R.id.item_label)
            val detail = row.findViewById<TextView>(R.id.item_detail)
            label.typeface = fonts.semibold
            detail.typeface = fonts.medium
            bindRow(row, item)
            row.id = View.generateViewId()
            row.setOnClickListener {
                when {
                    item.opens != null -> openPage(item.opens, fromIndex = index)
                    inPage != null -> { item.action(); closePage() }
                    item.keepOpen -> {
                        item.action()
                        // Refresh this row only; rebuilding the list makes the focused row jump.
                        itemsFor(which).getOrNull(index)?.let { bindRow(row, it) }
                    }
                    else -> { item.action(); closePanel(showControlsAfter = true) }
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
            else -> items.indexOfFirst { it.checked }.takeIf { it >= 0 } ?: 0
        }
        rows.getOrNull(target)?.let {
            it.requestFocus()
            // Land already highlighted instead of growing into place on every rebuild.
            it.stateListAnimator?.jumpToCurrentState()
            it.jumpDrawablesToCurrentState()
        }
    }

    private fun bindRow(row: View, item: Item) {
        row.findViewById<TextView>(R.id.item_label).text = item.label
        val detail = row.findViewById<TextView>(R.id.item_detail)
        detail.text = item.detail
        detail.visibility = if (item.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        val check = row.findViewById<ImageView>(R.id.item_check)
        check.setImageResource(if (item.opens != null) R.drawable.ic_chevron else R.drawable.ic_check)
        check.visibility = if (item.checked || item.opens != null) View.VISIBLE else View.INVISIBLE
    }

    private fun itemsFor(which: Panel): List<Item> = when (which) {
        Panel.AUDIO -> audioItems()
        Panel.SUBTITLES -> subtitleItems()
        Panel.PICTURE -> pictureItems()
        Panel.SPEED -> speedItems()
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
                    label = FormatNames.language(format) ?: activity.getString(R.string.track_unknown, number),
                    detail = FormatNames.audioDetail(format),
                    checked = group.isTrackSelected(i),
                ) { selectTrack(group, i) }
            }
        }
        items += Item(
            activity.getString(R.string.audio_default_language),
            languageName(prefs.audioLanguage, R.string.audio_language_auto),
            checked = false,
            opens = languagePage(R.string.audio_default_language, R.string.audio_language_auto, { prefs.audioLanguage }) {
                prefs.audioLanguage = it
                onTrackDefaultsChanged?.invoke()
            },
        )
        items += Item(
            activity.getString(R.string.audio_output),
            activity.getString(if (prefs.decodeAudio) R.string.audio_output_decode else R.string.audio_output_passthrough),
            checked = false,
            opens = Page(activity.getString(R.string.audio_output)) {
                fun choice(label: Int, detail: Int, decode: Boolean) = Item(
                    activity.getString(label), activity.getString(detail), prefs.decodeAudio == decode,
                ) {
                    if (prefs.decodeAudio != decode) {
                        prefs.decodeAudio = decode
                        onAudioOutputChanged?.invoke()
                    }
                }
                listOf(
                    choice(R.string.audio_output_passthrough, R.string.audio_output_passthrough_detail, decode = false),
                    choice(R.string.audio_output_decode, R.string.audio_output_decode_detail, decode = true),
                )
            },
        )
        return items
    }

    private fun languagePage(title: Int, noPreference: Int, current: () -> String, pick: (String) -> Unit) =
        Page(activity.getString(title)) {
            PlayerPrefs.LANGUAGES.map { language ->
                Item(
                    language.name.ifEmpty { activity.getString(noPreference) },
                    detail = null,
                    checked = current() == language.code,
                ) { pick(language.code) }
            }
        }

    private fun languageName(code: String, noPreference: Int) =
        PlayerPrefs.LANGUAGES.firstOrNull { it.code == code && code.isNotEmpty() }?.name ?: activity.getString(noPreference)


    private fun subtitleItems(): List<Item> {
        val exo = player ?: return emptyList()
        val textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        var anySelected = false
        val tracks = mutableListOf<Item>()
        var number = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                number++
                if (!group.isTrackSupported(i, true)) continue
                val format = group.getTrackFormat(i)
                val selected = !textDisabled && group.isTrackSelected(i)
                anySelected = anySelected || selected
                val forced = if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) " · forced" else ""
                tracks += Item(
                    label = FormatNames.language(format) ?: activity.getString(R.string.track_unknown, number),
                    detail = FormatNames.textCodec(format) + forced,
                    checked = selected,
                ) { selectTrack(group, i) }
            }
        }
        val size = PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize]
        return buildList {
            add(Item(activity.getString(R.string.subs_off), activity.getString(R.string.subs_off_detail), !anySelected) {
                player?.let {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
            })
            addAll(tracks)
            val mode = prefs.subtitleMode
            add(Item(
                activity.getString(R.string.subs_default),
                activity.getString(subtitleModeLabel(mode)),
                checked = false,
                opens = Page(activity.getString(R.string.subs_default)) {
                    PlayerPrefs.SubtitleMode.entries.map { choice ->
                        Item(activity.getString(subtitleModeLabel(choice)), activity.getString(subtitleModeDetail(choice)), prefs.subtitleMode == choice) {
                            prefs.subtitleMode = choice
                            onTrackDefaultsChanged?.invoke()
                        }
                    }
                },
            ))
            if (mode == PlayerPrefs.SubtitleMode.ALWAYS) {
                add(Item(
                    activity.getString(R.string.subs_language),
                    languageName(prefs.subtitleLanguage, R.string.subs_language_device),
                    checked = false,
                    opens = languagePage(R.string.subs_language, R.string.subs_language_device, { prefs.subtitleLanguage }) {
                        prefs.subtitleLanguage = it
                        onTrackDefaultsChanged?.invoke()
                    },
                ))
            }
            add(Item(
                activity.getString(R.string.subs_size),
                size.name,
                checked = false,
                opens = Page(activity.getString(R.string.subs_size)) {
                    PlayerPrefs.SUBTITLE_SIZES.mapIndexed { index, choice ->
                        Item(choice.name, detail = null, checked = prefs.subtitleSize == index) {
                            prefs.subtitleSize = index
                            applySubtitleStyle()
                        }
                    }
                },
            ))
        }
    }

    private fun subtitleModeLabel(mode: PlayerPrefs.SubtitleMode) = when (mode) {
        PlayerPrefs.SubtitleMode.AUTO -> R.string.subs_mode_auto
        PlayerPrefs.SubtitleMode.OFF -> R.string.subs_mode_off
        PlayerPrefs.SubtitleMode.FORCED_ONLY -> R.string.subs_mode_forced
        PlayerPrefs.SubtitleMode.ALWAYS -> R.string.subs_mode_always
    }

    private fun subtitleModeDetail(mode: PlayerPrefs.SubtitleMode) = when (mode) {
        PlayerPrefs.SubtitleMode.AUTO -> R.string.subs_mode_auto_detail
        PlayerPrefs.SubtitleMode.OFF -> R.string.subs_mode_off_detail
        PlayerPrefs.SubtitleMode.FORCED_ONLY -> R.string.subs_mode_forced_detail
        PlayerPrefs.SubtitleMode.ALWAYS -> R.string.subs_mode_always_detail
    }

    private fun speedItems(): List<Item> = SPEED_CHOICES.map { speed ->
        Item(speedName(speed), detail = null, checked = SPEEDS[speedIndex] == speed) { setSpeed(speed) }
    }

    private fun pictureItems(): List<Item> {
        fun mode(label: Int, detail: Int, mode: Int) = Item(
            activity.getString(label), activity.getString(detail), prefs.resizeMode == mode,
        ) {
            prefs.resizeMode = mode
            playerView.resizeMode = mode
        }
        return listOf(
            mode(R.string.picture_fit, R.string.picture_fit_detail, AspectRatioFrameLayout.RESIZE_MODE_FIT),
            mode(R.string.picture_fill, R.string.picture_fill_detail, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            mode(R.string.picture_stretch, R.string.picture_stretch_detail, AspectRatioFrameLayout.RESIZE_MODE_FILL),
            Item(
                activity.getString(R.string.picture_match),
                activity.getString(if (prefs.matchFrameRate) R.string.picture_match_on else R.string.picture_match_off),
                prefs.matchFrameRate,
                keepOpen = true,
            ) { prefs.matchFrameRate = !prefs.matchFrameRate },
        )
    }

    private fun moreItems(): List<Item> = listOf(
        Item(
            activity.getString(R.string.more_stats),
            activity.getString(if (stats.isVisible) R.string.on else R.string.off),
            stats.isVisible,
            keepOpen = true,
        ) { stats.toggle() },
        Item(
            activity.getString(R.string.more_speed),
            speedLabel(),
            checked = false,
            opens = Page(activity.getString(R.string.more_speed)) { speedItems() },
        ),
    )

    private fun selectTrack(group: Tracks.Group, index: Int) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun kickerFor(which: Panel) = when (which) {
        Panel.AUDIO -> R.string.control_audio
        Panel.SUBTITLES -> R.string.control_subtitles
        Panel.PICTURE -> R.string.control_picture
        Panel.SPEED -> R.string.control_speed
        Panel.MORE -> R.string.control_more
    }

    private fun titleFor(which: Panel) = when (which) {
        Panel.AUDIO -> R.string.panel_audio
        Panel.SUBTITLES -> R.string.panel_subtitles
        Panel.PICTURE -> R.string.panel_picture
        Panel.SPEED -> R.string.more_speed
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
                this.text = text
                textSize = 12f
                typeface = fonts.bold
                letterSpacing = 0.04f
                setTextColor(ContextCompat.getColor(activity, R.color.ink))
                setBackgroundResource(R.drawable.badge_bg)
                val h = (8 * density).toInt()
                val v = (4 * density).toInt()
                setPadding(h, v, h, v)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (8 * density).toInt()
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
        view.setStyle(
            CaptionStyleCompat(
                0xFFFFFFFF.toInt(),
                0x00000000,
                0x00000000,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                0xFF000000.toInt(),
                fonts.semibold,
            ),
        )
        view.setFractionalTextSize(PlayerPrefs.SUBTITLE_SIZES[prefs.subtitleSize].fraction)
        view.setBottomPaddingFraction(if (controlsVisible) SUBTITLE_LIFT_CONTROLS else SUBTITLE_LIFT_REST)
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
            updatePlayButton()
        }

        override fun onPlaybackStateChanged(state: Int) = updatePlayButton()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayButton()

        override fun onTracksChanged(tracks: Tracks) {
            updateBadges(tracks)
            if (page == null && (panel == Panel.AUDIO || panel == Panel.SUBTITLES)) buildPanel(focusIndex = panelItems.indexOfChild(panelItems.focusedChild))
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speedButton.text = if (playbackParameters.speed == 1f) activity.getString(R.string.control_speed) else speedLabel()
        }
    }

    private companion object {
        val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        /** The same speeds, slowest first, as the speed list shows them. */
        val SPEED_CHOICES = SPEEDS.sorted()
        const val BACK_MS = 10_000L
        const val FORWARD_MS = 30_000L
        const val EXIT_CONFIRM_MS = 2_500L
        const val AUTO_HIDE_MS = 4_000L
        const val PROGRESS_MS = 500L
        const val TOAST_MS = 800L
        const val SHOW_MS = 240L
        const val HIDE_MS = 220L
        const val PANEL_MS = 320L
        const val SUBTITLE_LIFT_REST = 0.08f
        const val SUBTITLE_LIFT_CONTROLS = 0.26f

        fun formatTime(ms: Long): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
