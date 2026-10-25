package com.thefactor1.player.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Phones and tablets get the touch controls; a TV, or anything without a touchscreen, keeps the
 * remote-first ones exactly as they are.
 */
object TouchMode {
    fun isOn(context: Context): Boolean {
        val pm = context.packageManager
        val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        return !tv && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
