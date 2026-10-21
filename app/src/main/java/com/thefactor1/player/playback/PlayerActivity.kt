package com.thefactor1.player.playback

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.thefactor1.player.BuildConfig
import com.thefactor1.player.R
import com.thefactor1.player.launch.LaunchRequest
import com.thefactor1.player.launch.PlaybackResult

/**
 * Plays one stream handed over by another app, then returns the watched position to it.
 * The player lives only between onStart and onStop, so nothing runs in the background.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var request: LaunchRequest? = null

    // Survive onStop/onStart (e.g. the home button) without restarting from the beginning.
    private var resumePositionMs = C.TIME_UNSET
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
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
        if (loadRequest(intent)) initializePlayer()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
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

        val renderers = DefaultRenderersFactory(this)
            // If a hardware decoder fails to start, try the next one before giving up.
            .setEnableDecoderFallback(true)

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

        exo.addListener(listener)
        playerView.player = exo
        player = exo

        val start = if (resumePositionMs != C.TIME_UNSET) resumePositionMs else req.startPositionMs
        exo.setMediaItem(buildMediaItem(req), start)
        exo.playWhenReady = true
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
        exo.removeListener(listener)
        playerView.player = null
        exo.release()
        player = null
    }

    private fun capturePosition(exo: ExoPlayer) {
        lastPositionMs = exo.currentPosition
        if (exo.duration != C.TIME_UNSET) lastDurationMs = exo.duration
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                completed = true
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.error_playback, error.errorCodeName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private companion object {
        const val STATE_POSITION = "position"
    }
}
