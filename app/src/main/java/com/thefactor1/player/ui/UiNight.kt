package com.thefactor1.player.ui

import android.content.Context
import android.content.res.Configuration

/**
 * Which palette an activity draws with. Broadsheet's paper colours live in res/values-notnight, so
 * every activity sets night mode itself rather than inheriting the TV's: playback always night,
 * Home "not night" only under Broadsheet.
 */
object UiNight {
    fun force(base: Context, night: Boolean): Context {
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(config)
    }
}
