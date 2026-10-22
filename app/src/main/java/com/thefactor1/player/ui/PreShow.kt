package com.thefactor1.player.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.thefactor1.player.R

/**
 * The pre-show: while a stream loads, the screen is a dark projection booth. The film's logo sits
 * in the middle, a log types out what the player actually found (picture, what the display is
 * given, sound, frame rate) as each becomes known, and a cue mark in the corner fills as the
 * stream buffers. When the picture is moving, the cue mark flashes twice like a reel change and
 * the film cuts in. It never holds playback: the film is already playing under the last half second.
 */
@OptIn(UnstableApi::class)
class PreShow(
    val view: View,
    private val fonts: Fonts,
    private val display: () -> DisplayInfo,
) : AnalyticsListener {

    private val context = view.context
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val block: View = view.findViewById(R.id.booth_block)
    private val logo: ImageView = view.findViewById(R.id.booth_logo)
    private val title: TextView = view.findViewById(R.id.booth_title)
    private val kicker: TextView = view.findViewById(R.id.booth_kicker)
    private val episode: TextView = view.findViewById(R.id.booth_episode)
    private val log: LinearLayout = view.findViewById(R.id.booth_log)
    private val status: TextView = view.findViewById(R.id.booth_status)
    private val cue: CueMarkView = view.findViewById(R.id.booth_cue)

    private val sourceRow = Row("SOURCE")
    private val pictureRow = Row("PICTURE")
    private val outputRow = Row("OUTPUT")
    private val soundRow = Row("SOUND")
    private val rateRow = Row("RATE")

    private var player: ExoPlayer? = null
    private var video: Format? = null
    private var audio: Format? = null
    private var passthrough: Boolean? = null
    private var frameRate = -1f
    private var progress = 0f
    private var revealing = false

    val isShowing: Boolean get() = view.visibility == View.VISIBLE

    init {
        fonts.apply(fonts.bold, title)
        fonts.apply(fonts.semibold, kicker, episode)
        fonts.apply(fonts.mono, status)
        listOf(sourceRow, pictureRow, outputRow, soundRow, rateRow).forEach { log.addView(it.view) }
    }

    fun attach(exo: ExoPlayer, sourceApp: String?) {
        player = exo
        exo.addAnalyticsListener(this)
        sourceRow.set(sourceApp)
        readTracks(exo.currentTracks)
        if (isShowing) startPolling()
    }

    fun detach() {
        player?.removeAnalyticsListener(this)
        player = null
        handler.removeCallbacksAndMessages(null)
        frameRate = -1f
    }

    /** A new wait begins: the log starts over and the cue mark empties. */
    fun begin(line: String) {
        revealing = false
        block.animate().cancel()
        block.alpha = 1f
        cue.reset()
        progress = 0f
        passthrough = null
        video = null
        audio = null
        for (row in listOf(pictureRow, outputRow, soundRow, rateRow)) row.set(null)
        status.text = line
        player?.let { readTracks(it.currentTracks) }
        showRate()
    }

    fun show() {
        view.visibility = View.VISIBLE
        startPolling()
    }

    fun hide() {
        handler.removeCallbacks(poll)
        view.visibility = View.GONE
        revealing = false
        block.animate().cancel()
        block.alpha = 1f
    }

    fun setStatus(line: String) {
        status.text = line
    }

    fun setHeader(titleText: CharSequence?, kickerText: String?, episodeText: String?) {
        title.text = titleText
        title.visibility = if (logo.visibility != View.VISIBLE && !titleText.isNullOrEmpty()) View.VISIBLE else View.GONE
        kicker.text = kickerText?.uppercase()
        kicker.visibility = if (kickerText.isNullOrEmpty()) View.GONE else View.VISIBLE
        episode.text = episodeText
        episode.visibility = if (episodeText.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** [fadeMs] is shorter for a logo that was already on the box, so it doesn't look late. */
    fun setLogo(bitmap: Bitmap?, fadeMs: Long = LOGO_FADE_MS) {
        logo.setImageBitmap(bitmap)
        logo.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        title.visibility = if (bitmap == null && !title.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        if (bitmap != null) {
            logo.alpha = 0f
            logo.animate().alpha(1f).setDuration(fadeMs).start()
        }
    }

    /** The frame rate the display was matched to (or left at), once the switch is done. */
    fun setFrameRate(fps: Float) {
        frameRate = fps
        showRate()
        video?.let { outputRow.set(PreShowLines.output(it, display())) }
    }

    /** The cue mark flashes twice while the log fades, then [onDone] lets the film show. */
    fun reveal(onDone: () -> Unit) {
        revealing = true
        handler.removeCallbacks(poll)
        block.animate().cancel()
        block.animate().alpha(0f).setDuration(FADE_MS).withLayer().start()
        cue.flash(onDone)
    }

    private fun showRate() {
        rateRow.set(if (frameRate > 0f) PreShowLines.rate(frameRate, display().refreshRate) else null)
    }

    private fun readTracks(tracks: Tracks) {
        if (tracks.isEmpty) return
        video = selected(tracks, C.TRACK_TYPE_VIDEO)
        audio = selected(tracks, C.TRACK_TYPE_AUDIO)
        pictureRow.set(video?.let { PreShowLines.picture(it) })
        outputRow.set(video?.let { PreShowLines.output(it, display()) })
        soundRow.set(audio?.let { PreShowLines.sound(it, passthrough) })
    }

    private fun selected(tracks: Tracks, type: Int): Format? {
        for (group in tracks.groups) {
            if (group.type != type || !group.isSelected) continue
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
        return null
    }

    // ---- Player events, on the main thread -----------------------------------------------------

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        if (isShowing && !revealing) readTracks(tracks)
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        passthrough = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        if (isShowing && !revealing) audio?.let { soundRow.set(PreShowLines.sound(it, passthrough)) }
    }

    // ---- The cue mark --------------------------------------------------------------------------

    private fun startPolling() {
        handler.removeCallbacks(poll)
        if (player != null && !revealing) handler.post(poll)
    }

    // Playback starts once this much is buffered (Media3's default), so the ring is full then.
    private val poll = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val now = if (exo.playbackState == Player.STATE_READY) 1f else exo.totalBufferedDuration / BUFFER_FOR_PLAYBACK_MS
            // Never runs backwards, e.g. when the frame-rate check seeks back to the start.
            progress = maxOf(progress, now.coerceIn(0f, 1f))
            cue.progress = progress
            if (isShowing && !revealing) handler.postDelayed(this, POLL_MS)
        }
    }

    // ---- One line of the log, typed in -----------------------------------------------------------

    private inner class Row(key: String) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (ROW_GAP_DP * density).toInt()
            }
        }
        private val value = text(R.color.ink)
        private var target = ""
        private var typing: ValueAnimator? = null

        init {
            view.addView(text(R.color.ink_faint).apply { text = key }, LinearLayout.LayoutParams((KEY_WIDTH_DP * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            view.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        /** Types [line] in from where it differs from what shows, or clears the row for null. */
        fun set(line: String?) {
            if (line == target) return
            typing?.cancel()
            target = line.orEmpty()
            if (line == null) {
                value.text = ""
                view.visibility = View.INVISIBLE
                return
            }
            view.visibility = View.VISIBLE
            val shown = value.text
            var from = 0
            while (from < shown.length && from < line.length && shown[from] == line[from]) from++
            if (from == line.length) {
                value.text = line
                return
            }
            typing = ValueAnimator.ofInt(from, line.length).apply {
                duration = minOf((line.length - from) * CHAR_MS, MAX_TYPE_MS)
                interpolator = LinearInterpolator()
                addUpdateListener { value.text = line.substring(0, it.animatedValue as Int) }
                start()
            }
        }

        private fun text(color: Int) = TextView(context).apply {
            typeface = fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LOG_TEXT_SP)
            setTextColor(ContextCompat.getColor(context, color))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
        }
    }

    private companion object {
        const val BUFFER_FOR_PLAYBACK_MS = 2_500f
        const val POLL_MS = 100L
        const val FADE_MS = 220L
        const val LOGO_FADE_MS = 500L
        const val CHAR_MS = 14L
        const val MAX_TYPE_MS = 480L
        const val LOG_TEXT_SP = 15f
        const val KEY_WIDTH_DP = 104f
        const val ROW_GAP_DP = 6f
    }
}
