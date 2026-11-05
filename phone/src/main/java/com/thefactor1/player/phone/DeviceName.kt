package com.thefactor1.player.phone

import android.content.Context
import android.os.Build
import android.provider.Settings

/** What the TV calls this phone: the name set in its settings, else the make and model. */
object DeviceName {
    fun of(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}
