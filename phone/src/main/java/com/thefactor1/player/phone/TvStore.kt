package com.thefactor1.player.phone

import android.content.Context

/** The TV this phone is paired with: where it was last seen, and the token it gave us. */
class TvStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tv", Context.MODE_PRIVATE)

    val paired: Boolean get() = token != null && host != null

    var host: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** The TV's advertised name, used to find it again if its address changes. */
    var name: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    fun api(): TvApi? {
        val host = host ?: return null
        return TvApi(host, port, token)
    }

    fun forget() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "name"
        const val DEFAULT_PORT = 8765
    }
}
