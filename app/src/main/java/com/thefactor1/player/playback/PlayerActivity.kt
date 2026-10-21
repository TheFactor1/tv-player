package com.thefactor1.player.playback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
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
import com.thefactor1.player.launch.IntentContract
import com.thefactor1.player.launch.LaunchRequest
import com.thefactor1.player.launch.PlaybackResult
import com.thefactor1.player.launch.ReleaseName
import com.thefactor1.player.ui.CinemaController
import com.thefactor1.player.ui.FormatNames
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
    private var player: ExoPlayer? = null
    private var request: LaunchRequest? = null

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
    private var longPressConsumed = false

    // What the fallback chain has already tried for the current stream.
    private var tried = FallbackPolicy.Tried()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        switcher = DisplayModeSwitcher(this)
        stats = StatsOverlay(findViewById<TextView>(R.id.stats_overlay), switcher)
        prefs = PlayerPrefs(this)
        controller = CinemaController(this, playerView, prefs, stats, onExit = ::finish)
        controller.onRetry = ::retryFromScratch
        controller.onOpenElsewhere = ::openInAnotherPlayer
        controller.onTrackDefaultsChanged = { player?.let(::applyTrackDefaults) }
        controller.onAudioOutputChanged = { if (player != null) rebuildPlayer(getString(R.string.loading_audio_output)) }
        // Android 13+ delivers Back through this callback instead of as a key event.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                controller.onBack()
            }
        }
        hideSystemBars()
        if (!loadRequest(intent)) return
        savedInstanceState?.getLong(STATE_POSITION, C.TIME_UNSET)?.let { resumePositionMs = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        releasePlayer()
        resumePositionMs = C.TIME_UNSET
        completed = false
        tried = FallbackPolicy.Tried()
        controller.showLoading()
        if (loadRequest(intent)) initializePlayer()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
        switcher.reset()
    }

    /** Long-press OK, or the Info key, toggles the stats overlay; everything else goes to the controls. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        outState.putLong(STATE_POSITION, player?.currentPosition ?: resumePositionMs)
    }

    override fun finish() {
        player?.let { capturePosition(it) }
        if (request != null) {
            setResult(RESULT_OK, PlaybackResult.build(lastPositionMs, lastDurationMs, completed))
        }
        super.finish()
    }

    private fun loadRequest(intent: Intent): Boolean {
        request = LaunchRequest.from(intent)
        if (request == null) {
            Toast.makeText(this, R.string.error_no_stream, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            super.finish()
            return false
        }
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

        val renderers = PlayerRenderersFactory(this, tried.dolbyVisionAsHdr10, tried.pcmAudio || prefs.decodeAudio)
        stats.fallbackNote = listOfNotNull(
            getString(R.string.fallback_note_dolby_vision).takeIf { tried.dolbyVisionAsHdr10 },
            getString(R.string.fallback_note_pcm).takeIf { tried.pcmAudio },
        ).joinToString(", ").ifEmpty { null }

        val exo = ExoPlayer.Builder(this, renderers)
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
        controller.attach(exo, name?.title, name?.detail, sourceAppName())
        playerView.player = exo
        player = exo

        val start = if (resumePositionMs != C.TIME_UNSET) resumePositionMs else req.startPositionMs
        exo.setMediaItem(buildMediaItem(req), start)
        awaitingDisplayMatch = true
        exo.playWhenReady = false
        exo.prepare()
    }

    private fun buildMediaItem(req: LaunchRequest): MediaItem {
        val subtitles = req.subtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(sub.uri)
                .setMimeType(sub.mimeType)
                .setLabel(sub.name)
                .setSelectionFlags(if (sub.selected) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(req.uri)
            .setMimeType(req.mimeHint)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(req.title).build())
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    private fun releasePlayer() {
        val exo = player ?: return
        capturePosition(exo)
        resumePositionMs = exo.currentPosition
        stopMeasuring(exo)
        exo.removeListener(listener)
        stats.detach()
        controller.detach()
        playerView.player = null
        exo.release()
        player = null
    }

    private fun capturePosition(exo: ExoPlayer) {
        lastPositionMs = exo.currentPosition
        if (exo.duration != C.TIME_UNSET) lastDurationMs = exo.duration
    }

    /** Tries the next fallback for [error], or shows the error card when none is left. */
    private fun handleFailure(error: PlaybackException) {
        val exo = player ?: return
        val failingFormat = (error as? ExoPlaybackException)?.rendererFormat
        val isDolbyVision = failingFormat?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
            exo.videoFormat?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        // A viewer who already chose to decode audio has nothing left to fall back to there.
        val alreadyTried = tried.copy(pcmAudio = tried.pcmAudio || prefs.decodeAudio)
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
            player?.playWhenReady = true
            return
        }
        if (frameRate <= 0f && hasVideo) {
            player?.let { startMeasuring(it) }
            return
        }
        if (switcher.wouldSwitch(frameRate)) controller.setLoadingLine(getString(R.string.loading_matching, formatRate(frameRate)))
        switcher.matchFrameRate(frameRate) { player?.playWhenReady = true }
    }

    private fun startMeasuring(exo: ExoPlayer) {
        frameRateEstimator.reset()
        measuring = true
        measureStartMs = exo.currentPosition
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
        switcher.matchFrameRate(fps) { player?.playWhenReady = true }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
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
                val video = player?.videoFormat
                matchDisplayThenPlay(video?.frameRate ?: -1f, hasVideo = video != null)
            }
            if (state == Player.STATE_ENDED) {
                completed = true
                finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A stream that plays again has earned a fresh set of network retries.
            if (isPlaying && tried.networkRetries > 0) tried = tried.copy(networkRetries = 0)
        }

        override fun onPlayerError(error: PlaybackException) = handleFailure(error)
    }

    private companion object {
        const val STATE_POSITION = "position"

        // Key repeats arrive about every 50 ms after a 500 ms delay; one repeat marks a long press.
        const val LONG_PRESS_REPEATS = 1

        const val MEASURE_SPEED = 2f
        const val MEASURE_TIMEOUT_MS = 8_000L
    }
}
