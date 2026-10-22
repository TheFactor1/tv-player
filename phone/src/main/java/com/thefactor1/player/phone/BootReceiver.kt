package com.thefactor1.player.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Picks the now-playing notification back up after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NowPlayingService.start(context)
        }
    }
}
