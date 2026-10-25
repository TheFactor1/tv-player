package com.thefactor1.player.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import com.thefactor1.player.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch on the picture, for phones: a tap shows or hides the controls, a double tap on either side
 * skips (and each further tap there keeps skipping, as on a phone's own video app), a double tap
 * in the middle plays or pauses, and a vertical swipe sets brightness on the left half and volume
 * on the right, with a small pill at the top showing the level.
 */
@SuppressLint("ClickableViewAccessibility")
class TouchGestures(
    private val activity: Activity,
    private val surface: View,
    /** False while loading, or while a menu is open: taps still arrive, but swipes don't adjust. */
    private val canAdjust: () -> Boolean,
    private val onTap: () -> Unit,
    /** -1 the left side, 1 the right, 0 the middle. */
    private val onDoubleTap: (side: Int) -> Unit,
) {
    private enum class Level { BRIGHTNESS, VOLUME }

    private val density = activity.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val pill: View = activity.findViewById(R.id.touch_level)
    private val pillIcon: ImageView = activity.findViewById(R.id.touch_level_icon)
    private val pillBar: SeekBarView = activity.findViewById(R.id.touch_level_bar)

    private var downX = 0f
    private var downY = 0f
    private var adjusting: Level? = null
    private var anchorY = 0f
    private var startLevel = 0f
    private var lastVolumeIndex = -1

    // A double tap on a side starts a run: further single taps there skip at once, without waiting.
    private var runSide = 0
    private var runUntil = 0L
    private var tapTakenByRun = false

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val side = sideOf(e.x, surface.width)
            if (side != 0 && side == runSide && SystemClock.uptimeMillis() < runUntil) {
                tapTakenByRun = true
                doubleTap(side)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tapTakenByRun) tapTakenByRun = false else onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            tapTakenByRun = false
            doubleTap(sideOf(e.x, surface.width))
            return true
        }
    })

    init {
        pillBar.setProgressColor(activity.getColor(R.color.ink))
        surface.setOnTouchListener { view, event -> onTouch(view, event) }
        surface.visibility = View.VISIBLE
    }

    private fun doubleTap(side: Int) {
        if (side != 0) {
            runSide = side
            runUntil = SystemClock.uptimeMillis() + RUN_MS
        }
        onDoubleTap(side)
    }

    private fun sideOf(x: Float, width: Int): Int = when {
        x < width * SIDE_FRACTION -> -1
        x > width * (1f - SIDE_FRACTION) -> 1
        else -> 0
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                adjusting = null
            }
            MotionEvent.ACTION_MOVE -> if (adjusting == null && event.pointerCount == 1 && canAdjust()) {
                val dy = event.y - downY
                val dx = event.x - downX
                // Clear of the screen's top and bottom edges, where the system's own swipes start.
                val guard = EDGE_GUARD_DP * density
                if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2 && downY > guard && downY < view.height - guard) {
                    val level = if (downX < view.width / 2f) Level.BRIGHTNESS else Level.VOLUME
                    if (level == Level.BRIGHTNESS || !audio.isVolumeFixed) {
                        adjusting = level
                        anchorY = event.y
                        startLevel = current(level)
                        // What began as a tap is a swipe now.
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        detector.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
            }
        }
        val level = adjusting
        if (level == null) {
            detector.onTouchEvent(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // A swipe the height of most of the screen runs the whole range.
                val travel = (view.height * SWIPE_RANGE).coerceAtLeast(1f)
                set(level, (startLevel + (anchorY - event.y) / travel).coerceIn(0f, 1f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                adjusting = null
                hidePillLater()
            }
        }
        return true
    }

    private fun current(level: Level): Float = when (level) {
        Level.BRIGHTNESS -> activity.window.attributes.screenBrightness.takeIf { it >= 0f }
            ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        Level.VOLUME -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            lastVolumeIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            lastVolumeIndex / max.toFloat()
        }
    }

    private fun set(level: Level, value: Float) {
        when (level) {
            // Only this window, and only while it shows: the phone's own setting is left alone.
            Level.BRIGHTNESS -> activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = value.coerceAtLeast(MIN_BRIGHTNESS)
            }
            Level.VOLUME -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val index = (value * max).roundToInt()
                if (index != lastVolumeIndex) {
                    lastVolumeIndex = index
                    // No flags: the pill stands in for the system's volume panel.
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
                }
            }
        }
        showPill(level, value)
    }

    private fun showPill(level: Level, value: Float) {
        pillIcon.setImageResource(
            when {
                level == Level.BRIGHTNESS -> R.drawable.ic_brightness
                value <= 0f -> R.drawable.ic_volume_off
                else -> R.drawable.ic_volume
            },
        )
        pillBar.update((value * LEVEL_STEPS).toLong(), 0L, LEVEL_STEPS)
        handler.removeCallbacks(hidePill)
        if (pill.visibility == View.VISIBLE && pill.alpha == 1f) return
        pill.animate().cancel()
        if (pill.visibility != View.VISIBLE) {
            pill.alpha = 0f
            pill.translationY = -Motion.RISE_DP * density / 2
            pill.visibility = View.VISIBLE
        }
        pill.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(Motion.ENTER_MS)
            .setInterpolator(Motion.enter).withLayer().start()
    }

    private fun hidePillLater() {
        handler.removeCallbacks(hidePill)
        handler.postDelayed(hidePill, PILL_HOLD_MS)
    }

    private val hidePill = Runnable {
        pill.animate().cancel()
        pill.animate().alpha(0f).setStartDelay(0).setDuration(Motion.EXIT_MS).setInterpolator(Motion.exit).withLayer()
            .withEndAction { pill.visibility = View.INVISIBLE }.start()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        pill.animate().cancel()
        pill.alpha = 0f
        pill.visibility = View.INVISIBLE
        adjusting = null
    }

    private companion object {
        const val SIDE_FRACTION = 0.38f
        const val RUN_MS = 700L
        const val EDGE_GUARD_DP = 36f
        const val SWIPE_RANGE = 0.75f
        const val MIN_BRIGHTNESS = 0.01f
        const val LEVEL_STEPS = 1000L
        const val PILL_HOLD_MS = 700L
    }
}
