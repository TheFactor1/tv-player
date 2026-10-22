package com.thefactor1.player.playback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import com.thefactor1.player.BuildConfig
import com.thefactor1.player.R
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.launch.IntentContract
import com.thefactor1.player.launch.LaunchRequest
import com.thefactor1.player.launch.PlaybackResult
import com.thefactor1.player.launch.ReleaseName
import com.thefactor1.player.resume.ResumePolicy
import com.thefactor1.player.resume.ResumeStore
import com.thefactor1.player.resume.TitleKeys
import com.thefactor1.player.ui.CinemaController
import com.thefactor1.player.ui.FormatNames
import com.thefactor1.player.ui.Fonts
import com.thefactor1.player.ui.ResumeChip
import java.util.Locale

/**
 * Plays one stream handed over by another app, then returns the watched position to it.
 * The player lives only between onStart and onStop, so nothing runs in the background.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var switcher: DisplayModeSwitcher
    private lateinit var stats: StatsOverlay
    private lateinit var prefs: PlayerPrefs
    private lateinit var controller: CinemaController
    private lateinit var resumeStore: ResumeStore
    private lateinit var resumeChip: ResumeChip
    private var player: ExoPlayer? = null
    private var request: LaunchRequest? = null

    // Where this title starts, the keys its position is saved under, and the saved entry used.
    private var titleKeys: List<String> = emptyList()
    private var start = ResumePolicy.Start(0L, ResumePolicy.Source.NONE)
    private var savedEntry: ResumeStore.Entry? = null
    private var startChecked = false
    private var chipShown = false
    private var chipTookOk = false
    private var chipOkAt = 0L

    // Survive onStop/onStart (e.g. the home button) without restarting from the beginning.
    private var resumePositionMs = C.TIME_UNSET
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var completed = false

    // Playback holds until we know the frame rate and the display has switched to match it.
    private var awaitingDisplayMatch = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameRateEstimator = FrameRateEstimator()
    @Volatile private var measuring = false
    private var measureStartMs = 0L
    private val audioDelay = AudioDelay()
    private val enhancement = AudioEnhancement()
    private val subtitleDelay = SubtitleDelay()
    private val decodeSwitch = DecodeSwitch()
    // Subtitles found online for this video, kept across player rebuilds; the newest is switched to once loaded.
    private val addedSubtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
    private var pendingSubtitleId: String? = null
    private var longPressConsumed = false

    // What the fallback chain has already tried for the current stream.
    private var tried = FallbackPolicy.Tried()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        switcher = DisplayModeSwitcher(this)
        stats = StatsOverlay(findViewById<TextView>(R.id.stats_overlay), switcher)
        stats.enhancement = enhancement
        prefs = PlayerPrefs(this)
        resumeStore = ResumeStore(this)
        controller = CinemaController(this, playerView, prefs, stats, enhancement, subtitleDelay, onExit = ::finish)
        resumeChip = ResumeChip(this, Fonts(this))
        controller.onRetry = ::retryFromScratch
        controller.onOpenElsewhere = ::openInAnotherPlayer
        controller.onAudioDelayChanged = { ms -> audioDelay.offsetUs = ms * 1_000L }
        controller.onNeedsDecodedAudio = { switchToDecodedAudio() }
        controller.onSpeedNeedsDecodedAudio = { speed ->
            // Once per stream: decoded audio that still can't change speed isn't switched again.
            if (!decodeSwitch.decode && !tried.pcmAudio && !prefs.decodeAudio) {
                switchToDecodedAudio { player?.setPlaybackSpeed(speed) }
            }
        }
        controller.onAddSubtitle = ::addSubtitle
        controller.onSkipCredits = {
            completed = true
            finish()
        }
        // Android 13+ delivers Back through this callback instead of as a key event.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                controller.onBack()
            }
        }
        hideSystemBars()
        if (!loadRequest(intent)) return
        // The first stream gets the pre-show too, not only the ones that follow.
        controller.showLoading()
        savedInstanceState?.getLong(STATE_POSITION, C.TIME_UNSET)?.let { resumePositionMs = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        releasePlayer()
        resumePositionMs = C.TIME_UNSET
        completed = false
        tried = FallbackPolicy.Tried()
        addedSubtitles.clear()
        pendingSubtitleId = null
        subtitleDelay.offsetUs = 0
        controller.showLoading()
        if (loadRequest(intent)) initializePlayer()
    }

    override fun onStart() {
        super.onStart()
        PhoneCompanion.start(this)
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        PhoneCompanion.stop()
        releasePlayer()
        switcher.reset()
    }

    /** Long-press OK, or the Info key, toggles the stats overlay; everything else goes to the controls. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleResumeChipKey(event)) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_I -> {
                if (event.action == KeyEvent.ACTION_UP) stats.toggle()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == LONG_PRESS_REPEATS) {
                    longPressConsumed = true
                    controller.cancelPendingPress()
                    stats.toggle()
                }
                if (longPressConsumed) {
                    if (event.action == KeyEvent.ACTION_UP) longPressConsumed = false
                    return true
                }
            }
        }
        if (controller.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_POSITION, player?.let { playbackPosition(it) } ?: resumePositionMs)
    }

    override fun finish() {
        player?.let { capturePosition(it) }
        request?.let { req ->
            val duration = lastDurationMs.takeIf { it > 0 } ?: req.durationHintMs
            rememberPosition()
            setResult(RESULT_OK, PlaybackResult.build(req.uri, lastPositionMs, duration, completed))
        }
        super.finish()
    }

    private fun loadRequest(intent: Intent): Boolean {
        val req = LaunchRequest.from(intent)
        request = req
        if (req == null) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            super.finish()
            return false
        }
        // Without a title extra the request falls back to the URL's last segment, which TitleKeys
        // already weighs as a file name.
        val title = req.title?.takeIf { it != req.uri.lastPathSegment }
        titleKeys = TitleKeys.from(title, req.fileName, req.uri.toString(), req.textExtras)
        savedEntry = resumeStore.find(titleKeys)
        start = ResumePolicy.start(req.startPositionMs, req.fromStart, savedEntry)
        startChecked = false
        chipShown = false
        return true
    }

    /** Files the current position under every key for this title, or forgets it once watched. */
    private fun rememberPosition() {
        if (titleKeys.isEmpty()) return
        val duration = lastDurationMs.takeIf { it > 0 }
            ?: request?.durationHintMs?.takeIf { it > 0 }
            ?: savedEntry?.durationMs
            ?: 0L
        when (ResumePolicy.onLeave(lastPositionMs, duration, completed)) {
            ResumePolicy.Leave.FORGET -> resumeStore.forget(titleKeys)
            ResumePolicy.Leave.SAVE -> resumeStore.save(titleKeys, lastPositionMs, duration)
            ResumePolicy.Leave.KEEP -> Unit
        }
    }

    // Saved every few seconds as well as on exit, so a crash or a pulled plug loses little.
    private val saveTick = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (exo.isPlaying && !measuring) {
                capturePosition(exo)
                rememberPosition()
            }
            mainHandler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    /**
     * Once the stream's length is known, checks the start fits it. A position remembered from
     * another stream of a different length (another cut, a mismatched episode) or one past the
     * end (an app sending another unit) starts from the top instead.
     */
    private fun checkStart(exo: ExoPlayer) {
        if (startChecked || exo.duration == C.TIME_UNSET) return
        startChecked = true
        if (start.source == ResumePolicy.Source.NONE) {
            pickUpLastWatched(exo)
            return
        }
        val duration = exo.duration
        val saved = savedEntry
        val pastEnd = start.positionMs >= duration - END_MARGIN_MS
        val wrongStream = start.source == ResumePolicy.Source.LOCAL && saved != null &&
            (!ResumePolicy.sameLength(saved.durationMs, duration) || ResumePolicy.isWatched(start.positionMs, duration))
        if (pastEnd || wrongStream) startOverInstead(exo)
    }

    /**
     * A stream nothing ties to a saved title (an obfuscated Usenet file, no title from the app)
     * carries on from the film watched last if its length all but matches: switching sources
     * mid-film. The chip says so, and a double OK starts over.
     */
    private fun pickUpLastWatched(exo: ExoPlayer) {
        val req = request ?: return
        if (req.fromStart || savedEntry != null || req.startPositionMs >= ResumePolicy.MIN_RESUME_MS) return
        val (last, lastKeys) = resumeStore.latest() ?: return
        val fits = ResumePolicy.lastWatchedFits(
            last, TitleKeys.isEpisode(lastKeys), TitleKeys.isEpisode(titleKeys), exo.duration, System.currentTimeMillis(),
        )
        if (!fits) return
        savedEntry = last
        start = ResumePolicy.Start(last.positionMs, ResumePolicy.Source.LOCAL)
        if (measuring) measureStartMs = last.positionMs else exo.seekTo(last.positionMs)
    }

    private fun startOverInstead(exo: ExoPlayer) {
        start = ResumePolicy.Start(0L, ResumePolicy.Source.NONE)
        restartFromBeginning(exo)
    }

    private fun restartFromBeginning(exo: ExoPlayer) {
        // While the frame rate is measured, the measurement returns to measureStartMs when done.
        if (measuring) measureStartMs = 0L else exo.seekTo(0L)
    }

    /** Once per launch, a few moments after the picture appears, says where playback resumed. */
    private fun maybeShowResumeChip(exo: ExoPlayer) {
        if (chipShown || measuring || start.source == ResumePolicy.Source.NONE) return
        if (start.positionMs < ResumePolicy.MIN_RESUME_MS) return
        chipShown = true
        val time = ResumeChip.formatTime(start.positionMs)
        val text = if (start.source == ResumePolicy.Source.LOCAL) {
            getString(R.string.resume_from_memory, time)
        } else {
            getString(R.string.resume_from_app, time)
        }
        mainHandler.postDelayed({ if (player === exo && exo.isPlaying) resumeChip.show(text) }, CHIP_DELAY_MS)
    }

    /**
     * Two quick presses of OK while the chip shows start over. The first press does what OK always
     * does (pause), so a viewer who only wants to pause is never sent back to the start; the
     * second is taken here and plays from the top. Any other key puts the chip away.
     */
    private fun handleResumeChipKey(event: KeyEvent): Boolean {
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (chipTookOk && isOk) {
            if (event.action == KeyEvent.ACTION_UP) chipTookOk = false
            return true
        }
        if (!resumeChip.isShowing) return false
        if (!isOk) {
            if (event.action == KeyEvent.ACTION_DOWN) resumeChip.hide()
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (event.eventTime - chipOkAt > DOUBLE_OK_MS) {
            chipOkAt = event.eventTime
            return false
        }
        chipOkAt = 0L
        chipTookOk = true
        player?.let {
            restartFromBeginning(it)
            it.playWhenReady = true
        }
        resumeChip.confirm(getString(R.string.resume_starting_over))
        return true
    }

    private fun initializePlayer() {
        val req = request ?: return
        if (player != null) return

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Player/${BuildConfig.VERSION_NAME} (Android TV)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(req.headers)

        audioDelay.offsetUs = prefs.audioDelayMs * 1_000L
        enhancement.nightMode = prefs.nightMode
        enhancement.dialogueBoost = prefs.dialogueBoost
        val renderers = PlayerRenderersFactory(
            this, tried.dolbyVisionAsHdr10, tried.pcmAudio || decodeByChoice(), audioDelay, enhancement, subtitleDelay,
            decodeSwitch,
        )
        stats.fallbackNote = listOfNotNull(
            getString(R.string.fallback_note_dolby_vision).takeIf { tried.dolbyVisionAsHdr10 },
            getString(R.string.fallback_note_pcm).takeIf { tried.pcmAudio },
        ).joinToString(", ").ifEmpty { null }

        val exo = ExoPlayer.Builder(this, renderers)
            .setLoadControl(StreamBuffer.loadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        applyTrackDefaults(exo)
        exo.addListener(listener)
        stats.attach(exo)
        val name = ReleaseName.parse(req.title)
        PhoneCompanion.launchSubtitles = req.subtitles.sortedByDescending { it.selected }
            .map { it.uri.toString() }.filter { it.startsWith("http://") || it.startsWith("https://") }
        controller.attach(exo, name?.title, name?.detail, sourceAppName(), TitleKeys.imdbId(titleKeys))
        playerView.player = exo
        player = exo

        // A rebuilt player (fallback, retry, coming back from Home) carries on where the last one was.
        val startAt = if (resumePositionMs != C.TIME_UNSET) resumePositionMs else start.positionMs
        exo.setMediaItem(buildMediaItem(req), startAt)
        awaitingDisplayMatch = true
        exo.playWhenReady = false
        exo.prepare()
        mainHandler.removeCallbacks(saveTick)
        mainHandler.postDelayed(saveTick, SAVE_EVERY_MS)
    }

    private fun buildMediaItem(req: LaunchRequest): MediaItem {
        val subtitles = req.subtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(sub.uri)
                .setMimeType(sub.mimeType)
                .setLabel(sub.name)
                .setSelectionFlags(if (sub.selected) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        } + addedSubtitles
        return MediaItem.Builder()
            .setUri(req.uri)
            .setMimeType(req.mimeHint)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(req.title).build())
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    /** The viewer asked for decoded audio, directly or by switching on sound shaping that needs it. */
    private fun decodeByChoice() = prefs.decodeAudio || prefs.nightMode || prefs.dialogueBoost

    /**
     * Moves from passthrough to decoded audio without stopping: the sink stops accepting Dolby and
     * DTS, and audio is switched off and on again so the renderers pick a decoder. The picture keeps
     * playing; sound drops out for a moment, the way it does when changing audio track.
     */
    private fun switchToDecodedAudio(then: () -> Unit = {}) {
        val exo = player ?: return
        decodeSwitch.decode = true
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        mainHandler.post {
            player?.let {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
                then()
            }
        }
    }

    /**
     * Adds a subtitle file found online as a new track. Media3 only takes new side-loaded tracks
     * with a new media item, so the stream re-opens at the same spot, and the new track is selected
     * once it shows up.
     */
    private fun addSubtitle(url: String, language: String?, label: String) {
        val exo = player ?: return
        val req = request ?: return
        val id = "online-${addedSubtitles.size + 1}"
        addedSubtitles += MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setId(id)
            .setMimeType(IntentContract.subtitleMimeType(url))
            .setLanguage(language)
            .setLabel(label)
            .build()
        pendingSubtitleId = id
        exo.setMediaItem(buildMediaItem(req), exo.currentPosition)
        exo.prepare()
    }

    private fun selectPendingSubtitle(exo: ExoPlayer, tracks: Tracks) {
        val id = pendingSubtitleId ?: return
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val trackId = group.getTrackFormat(i).id ?: continue
                if (trackId != id && !trackId.endsWith(":$id")) continue
                pendingSubtitleId = null
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                return
            }
        }
    }

    /**
     * With subtitles always on, swaps a forced track the selector landed on for the full one in
     * the same language. A pick from the panel is an override and is left alone.
     */
    private fun preferFullSubtitles(exo: ExoPlayer, tracks: Tracks) {
        if (prefs.subtitleMode != PlayerPrefs.SubtitleMode.ALWAYS) return
        val params = exo.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return
        if (params.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT }) return
        val slots = mutableListOf<Pair<Tracks.Group, Int>>()
        val candidates = mutableListOf<SubtitlePick.Track>()
        var selected = -1
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (group.isTrackSelected(i)) selected = slots.size
                slots += group to i
                candidates += SubtitlePick.Track(
                    language = format.language,
                    forced = FormatNames.isForced(format),
                    hearingImpaired = format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0,
                    commentary = format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0,
                    supported = group.isTrackSupported(i),
                )
            }
        }
        val better = SubtitlePick.fullInsteadOfForced(candidates, selected) ?: return
        val (group, index) = slots[better]
        exo.trackSelectionParameters = params.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun releasePlayer() {
        val exo = player ?: return
        capturePosition(exo)
        resumePositionMs = lastPositionMs
        if (request != null) rememberPosition()
        resumeChip.hide()
        stopMeasuring(exo)
        exo.removeListener(listener)
        stats.detach()
        controller.detach()
        playerView.player = null
        exo.release()
        player = null
    }

    private fun capturePosition(exo: ExoPlayer) {
        lastPositionMs = playbackPosition(exo)
        if (exo.duration != C.TIME_UNSET) lastDurationMs = exo.duration
    }

    /** The viewer's position; while the frame rate is measured, the spot it will return to. */
    private fun playbackPosition(exo: ExoPlayer): Long = if (measuring) measureStartMs else exo.currentPosition

    /** Tries the next fallback for [error], or shows the error card when none is left. */
    private fun handleFailure(error: PlaybackException) {
        val exo = player ?: return
        val failingFormat = (error as? ExoPlaybackException)?.rendererFormat
        val isDolbyVision = failingFormat?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            exo.videoFormat?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        // A viewer who already chose to decode audio has nothing left to fall back to there.
        val alreadyTried = tried.copy(pcmAudio = tried.pcmAudio || decodeByChoice())
        when (val action = FallbackPolicy.next(classify(error, failingFormat), isDolbyVision, alreadyTried)) {
            is FallbackPolicy.Action.Retry -> {
                tried = tried.copy(networkRetries = action.attempt)
                controller.showLoading(
                    getString(R.string.fallback_reconnecting, action.attempt, FallbackPolicy.MAX_NETWORK_RETRIES),
                    waitForNewFrame = false,
                )
                mainHandler.postDelayed({
                    player?.let {
                        it.prepare()
                        it.playWhenReady = true
                    }
                }, action.delayMs)
            }
            FallbackPolicy.Action.RejoinLive -> {
                exo.seekToDefaultPosition()
                exo.prepare()
            }
            FallbackPolicy.Action.PlayDolbyVisionAsHdr10 -> {
                tried = tried.copy(dolbyVisionAsHdr10 = true)
                rebuildPlayer(getString(R.string.fallback_dolby_vision))
            }
            FallbackPolicy.Action.DecodeAudioToPcm -> {
                tried = tried.copy(pcmAudio = true)
                rebuildPlayer(getString(R.string.fallback_pcm))
            }
            FallbackPolicy.Action.GiveUp -> controller.showError(errorMessage(error), error.errorCodeName)
        }
    }

    private fun classify(error: PlaybackException, failingFormat: Format?): FallbackPolicy.Failure = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> FallbackPolicy.Failure.NETWORK
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FallbackPolicy.Failure.BEHIND_LIVE_WINDOW
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> FallbackPolicy.Failure.AUDIO_OUTPUT
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            if (MimeTypes.isAudio(failingFormat?.sampleMimeType)) {
                FallbackPolicy.Failure.AUDIO_OUTPUT
            } else {
                FallbackPolicy.Failure.VIDEO_DECODER
            }
        else -> FallbackPolicy.Failure.OTHER
    }

    private fun applyTrackDefaults(exo: ExoPlayer) {
        exo.trackSelectionParameters = TrackDefaults.apply(
            exo.trackSelectionParameters,
            audioLanguage = prefs.audioLanguage,
            subtitleMode = prefs.subtitleMode,
            subtitleLanguage = prefs.subtitleLanguage,
            deviceLanguage = Locale.getDefault().language,
        )
    }

    /** A new player with the fallback switches flipped, carrying on from the same position. */
    private fun rebuildPlayer(line: String) {
        mainHandler.post {
            releasePlayer()
            controller.showLoading(line)
            initializePlayer()
        }
    }

    private fun retryFromScratch() {
        tried = FallbackPolicy.Tried()
        releasePlayer()
        controller.showLoading()
        initializePlayer()
    }

    /** Hands the stream, its headers and subtitles, and the current position to another player. */
    private fun openInAnotherPlayer() {
        val req = request ?: return
        player?.let { capturePosition(it) }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(req.uri, intent.type ?: "video/*")
            intent.extras?.let { putExtras(it) }
            putExtra(IntentContract.EXTRA_POSITION, lastPositionMs.toInt())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, getString(R.string.open_with))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, PlayerActivity::class.java)))
        try {
            startActivity(chooser)
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_other_player, Toast.LENGTH_LONG).show()
        }
    }

    /** Says what went wrong in words a viewer can act on. */
    private fun errorMessage(error: PlaybackException): String {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                val format = (error as? ExoPlaybackException)?.rendererFormat
                return getString(R.string.error_decoder, format?.let { describe(it) } ?: "this stream")
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return getString(R.string.error_network)
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                return getString(R.string.error_http, status)
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> return getString(R.string.error_parsing)
        }
        return getString(R.string.error_playback)
    }

    /** "4K Dolby Vision HEVC", "1080p H.264", "TrueHD 7.1". */
    private fun describe(format: Format): String {
        if (format.height <= 0) return FormatNames.audioDetail(format)
        val resolution = when {
            format.height >= 2000 || format.width >= 3800 -> "4K"
            else -> "${format.height}p"
        }
        val hdr = when {
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        val codec = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            if (format.codecs?.startsWith("dvav") == true || format.codecs?.startsWith("dva1") == true) "H.264" else "HEVC"
        } else {
            FormatNames.codec(format.sampleMimeType)
        }
        return listOfNotNull(resolution, hdr, codec).joinToString(" ")
    }

    /** The label of the app that opened us, e.g. "Stremio", for the info bar and loading card. */
    private fun sourceAppName(): String? {
        val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingPackage ?: return null
        if (pkg == packageName) return null
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun formatRate(rate: Float): String =
        String.format(java.util.Locale.US, "%.3f", rate).trimEnd('0').trimEnd('.')

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Matches the display to [frameRate] once, then starts playback. When the stream has video but
     * declares no frame rate, the first frames play silently behind the loading card to measure it,
     * so any display switch happens before the viewer sees a picture.
     */
    private fun matchDisplayThenPlay(frameRate: Float, hasVideo: Boolean) {
        if (!awaitingDisplayMatch) return
        awaitingDisplayMatch = false
        if (!prefs.matchFrameRate) {
            controller.noteFrameRate(frameRate)
            player?.playWhenReady = true
            return
        }
        if (frameRate <= 0f && hasVideo) {
            player?.let { startMeasuring(it) }
            return
        }
        if (switcher.wouldSwitch(frameRate)) controller.setLoadingLine(getString(R.string.loading_matching, formatRate(frameRate)))
        switcher.matchFrameRate(frameRate) {
            controller.noteFrameRate(frameRate)
            player?.playWhenReady = true
        }
    }

    private fun startMeasuring(exo: ExoPlayer) {
        frameRateEstimator.reset()
        measuring = true
        measureStartMs = exo.currentPosition
        stats.measuredFrameRate = 0f
        controller.holdLoading = true
        controller.setLoadingLine(getString(R.string.loading_measuring))
        // Audio off (passthrough ignores volume) and double speed: the frames arrive in half the time.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        exo.setPlaybackSpeed(MEASURE_SPEED)
        exo.setVideoFrameMetadataListener(frameTimes)
        exo.playWhenReady = true
        mainHandler.postDelayed(measureTimeout, MEASURE_TIMEOUT_MS)
    }

    /** Puts audio, speed and position back as they were and lets the picture show. */
    private fun finishMeasuring(exo: ExoPlayer, thenPlay: Boolean) {
        mainHandler.removeCallbacks(measureTimeout)
        exo.clearVideoFrameMetadataListener(frameTimes)
        exo.playWhenReady = false
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        exo.setPlaybackSpeed(1f)
        exo.seekTo(measureStartMs)
        controller.holdLoading = false
        if (thenPlay) exo.playWhenReady = true
    }

    // A stream too irregular to measure plays at the current rate rather than waiting forever.
    private val measureTimeout = Runnable {
        if (!measuring) return@Runnable
        measuring = false
        stats.measuredFrameRate = -1f
        player?.let { finishMeasuring(it, thenPlay = true) }
    }

    private fun stopMeasuring(exo: ExoPlayer) {
        measuring = false
        exo.clearVideoFrameMetadataListener(frameTimes)
        controller.holdLoading = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    // Runs on the playback thread for every frame, so it only does arithmetic until it has an answer.
    private val frameTimes = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        if (!measuring) return@VideoFrameMetadataListener
        val fps = frameRateEstimator.add(presentationTimeUs) ?: return@VideoFrameMetadataListener
        measuring = false
        mainHandler.post { onFrameRateMeasured(fps) }
    }

    /** Switches the display while the loading card still covers the screen, then starts from the top. */
    private fun onFrameRateMeasured(fps: Float) {
        val exo = player ?: return
        stats.measuredFrameRate = fps
        finishMeasuring(exo, thenPlay = false)
        if (switcher.wouldSwitch(fps)) controller.setLoadingLine(getString(R.string.loading_matching, formatRate(fps)))
        switcher.matchFrameRate(fps) {
            controller.noteFrameRate(fps)
            player?.playWhenReady = true
        }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            player?.let {
                selectPendingSubtitle(it, tracks)
                preferFullSubtitles(it, tracks)
            }
            if (!awaitingDisplayMatch || tracks.isEmpty) return
            val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            val frameRate = video?.let { group ->
                (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).frameRate }
            } ?: -1f
            matchDisplayThenPlay(frameRate, hasVideo = video != null)
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Some streams report no tracks before they are ready; never hold playback for them.
            if (state == Player.STATE_READY) {
                player?.let { checkStart(it) }
                val video = player?.videoFormat
                matchDisplayThenPlay(video?.frameRate ?: -1f, hasVideo = video != null)
            }
            if (state == Player.STATE_ENDED) {
                // A start past the end can finish before the length is ever known.
                val exo = player
                if (!startChecked && start.positionMs > 0 && exo != null) {
                    startChecked = true
                    startOverInstead(exo)
                    return
                }
                completed = true
                finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A stream that plays again has earned a fresh set of network retries.
            if (isPlaying && tried.networkRetries > 0) tried = tried.copy(networkRetries = 0)
            if (isPlaying) player?.let { maybeShowResumeChip(it) }
        }

        override fun onPlayerError(error: PlaybackException) = handleFailure(error)
    }

    private companion object {
        const val STATE_POSITION = "position"

        // Key repeats arrive about every 50 ms after a 500 ms delay; one repeat marks a long press.
        const val LONG_PRESS_REPEATS = 1

        const val SAVE_EVERY_MS = 10_000L
        const val END_MARGIN_MS = 5_000L
        const val CHIP_DELAY_MS = 700L
        const val DOUBLE_OK_MS = 600L

        const val MEASURE_SPEED = 2f
        const val MEASURE_TIMEOUT_MS = 6_000L
    }
}
