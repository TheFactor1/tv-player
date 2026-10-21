package com.thefactor1.player.playback

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.thefactor1.player.ui.FormatNames
import java.util.Locale

/**
 * What is really playing: codec, HDR format, decoder, display mode, whether audio is bitstreamed,
 * buffer and dropped frames. Refreshes once a second, and only while visible.
 */
@OptIn(UnstableApi::class)
class StatsOverlay(
    private val view: TextView,
    private val switcher: DisplayModeSwitcher,
) : AnalyticsListener {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var audioOutputEncoding: Int = C.ENCODING_INVALID

    /** Fallbacks in use for this stream, shown so it's clear why the output differs. */
    var fallbackNote: String? = null

    /** Frame rate measured from frame times, for streams that don't declare one: 0 while measuring, -1 if it couldn't be. */
    var measuredFrameRate: Float = 0f

    val isVisible: Boolean get() = view.visibility == View.VISIBLE

    fun attach(exo: ExoPlayer) {
        player = exo
        exo.addAnalyticsListener(this)
        if (isVisible) refresh.run()
    }

    fun detach() {
        player?.removeAnalyticsListener(this)
        player = null
        handler.removeCallbacks(refresh)
        videoDecoder = null
        audioDecoder = null
        audioOutputEncoding = C.ENCODING_INVALID
        measuredFrameRate = 0f
    }

    fun toggle() {
        if (isVisible) {
            view.visibility = View.GONE
            handler.removeCallbacks(refresh)
        } else {
            view.visibility = View.VISIBLE
            refresh.run()
        }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoder = decoderName
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        audioDecoder = null
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        audioOutputEncoding = audioTrackConfig.encoding
    }

    private val refresh = object : Runnable {
        override fun run() {
            view.text = render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun render(): String {
        val exo = player ?: return "No stream"
        val video = exo.videoFormat
        val audio = exo.audioFormat
        val mode = switcher.currentMode
        val rows = mutableListOf<Pair<String, String>>()

        if (video != null) {
            rows += "Video" to listOfNotNull(FormatNames.codec(video.sampleMimeType), video.codecs).joinToString(" · ")
            videoDecoder?.let { rows += "Decoder" to it }
            val fps = when {
                video.frameRate > 0 -> " · ${rate(video.frameRate)} fps"
                measuredFrameRate > 0 -> " · ${rate(measuredFrameRate)} fps (measured)"
                measuredFrameRate < 0 -> " · fps unknown"
                else -> " · measuring fps"
            }
            rows += "Frame" to "${video.width}×${video.height}$fps"
            rows += "HDR" to hdrName(video)
        }
        rows += "Display" to "${mode.physicalWidth}×${mode.physicalHeight} @ ${rate(mode.refreshRate)} Hz"
        if (audio != null) {
            rows += "Audio" to "${FormatNames.audioDetail(audio)} · ${audioOutput()}"
            FormatNames.language(audio)?.let { rows += "Language" to it }
        }
        val bitrate = video?.bitrate?.takeIf { it > 0 } ?: video?.averageBitrate?.takeIf { it > 0 }
        if (bitrate != null) rows += "Bitrate" to String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000f)
        rows += "Buffer" to "${exo.totalBufferedDuration / 1000} s ahead"
        exo.videoDecoderCounters?.let { rows += "Dropped" to "${it.droppedBufferCount} frames" }
        fallbackNote?.let { rows += "Fallback" to it }

        val width = rows.maxOf { it.first.length } + 2
        return rows.joinToString("\n") { (label, value) -> label.padEnd(width) + value }
    }

    private fun audioOutput(): String = when (audioOutputEncoding) {
        C.ENCODING_INVALID -> "starting"
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT ->
            "decoded to PCM" + (audioDecoder?.let { " ($it)" } ?: "")
        else -> "passthrough (${encodingName(audioOutputEncoding)})"
    }

    private fun hdrName(format: Format): String {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return "Dolby Vision"
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10 / HDR10+ (PQ)"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "Dolby Digital+"
        C.ENCODING_E_AC3_JOC -> "DD+ Atmos"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DTS_UHD_P2 -> "DTS:X"
        else -> "encoding $encoding"
    }

    private fun rate(value: Float): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private companion object {
        const val REFRESH_MS = 1_000L
    }
}
