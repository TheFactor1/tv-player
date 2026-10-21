package com.thefactor1.player.playback

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * Switches the TV to the refresh rate that suits the video, then waits for the switch to land
 * before playback starts, so the first frames are not lost to the HDMI resync.
 */
class DisplayModeSwitcher(private val activity: Activity) {

    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var targetModeId = 0
    private var onReady: (() -> Unit)? = null

    val currentMode: Display.Mode get() = display().mode

    /** True when [frameRate] would make the display change mode. */
    fun wouldSwitch(frameRate: Float): Boolean = targetFor(frameRate) != null

    /** Calls [ready] once the display suits [frameRate]: at once when no switch is needed. */
    fun matchFrameRate(frameRate: Float, ready: () -> Unit) {
        val target = targetFor(frameRate)
        if (target == null) {
            ready()
            return
        }
        targetModeId = target.id
        onReady = ready
        displayManager.registerDisplayListener(listener, handler)
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = target.id }
        handler.postDelayed(finishSwitch, SWITCH_TIMEOUT_MS)
    }

    /** Drops any pending switch and hands the display back to the system default. */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(listener)
        onReady = null
        if (activity.window.attributes.preferredDisplayModeId != 0) {
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }

    private val finishSwitch: Runnable = Runnable {
        displayManager.unregisterDisplayListener(listener)
        handler.removeCallbacksAndMessages(null)
        val ready = onReady ?: return@Runnable
        onReady = null
        // TVs keep the picture blank for a moment after the mode change; let it settle first.
        handler.postDelayed(ready, SETTLE_MS)
    }

    private val listener: DisplayManager.DisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display().displayId && display().mode.modeId == targetModeId) finishSwitch.run()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private fun targetFor(frameRate: Float): ModeInfo? {
        val display = display()
        return DisplayModeMatcher.choose(
            modes = display.supportedModes.map { it.toInfo() },
            current = display.mode.toInfo(),
            frameRate = frameRate,
        )
    }

    @Suppress("DEPRECATION")
    private fun display(): Display = activity.windowManager.defaultDisplay

    private fun Display.Mode.toInfo() = ModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)

    private companion object {
        const val SWITCH_TIMEOUT_MS = 4_000L
        const val SETTLE_MS = 500L
    }
}
