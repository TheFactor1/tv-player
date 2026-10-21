package com.thefactor1.player.resume

import android.content.Context
import androidx.core.content.edit

/**
 * Where each title was left, kept on the device. One position is written under every key
 * [TitleKeys] gives, and the oldest entries go once there are more than [MAX_KEYS].
 */
class ResumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    data class Entry(val positionMs: Long, val durationMs: Long, val savedAt: Long) {
        fun encode(): String = "$positionMs|$durationMs|$savedAt"

        companion object {
            fun decode(value: String?): Entry? {
                val parts = value?.split('|') ?: return null
                if (parts.size != 3) return null
                return Entry(
                    parts[0].toLongOrNull() ?: return null,
                    parts[1].toLongOrNull() ?: return null,
                    parts[2].toLongOrNull() ?: return null,
                )
            }
        }
    }

    /** The saved position under the strongest key that has one. */
    fun find(keys: List<String>): Entry? = keys.firstNotNullOfOrNull { Entry.decode(prefs.getString(it, null)) }

    fun save(keys: List<String>, positionMs: Long, durationMs: Long) {
        if (keys.isEmpty()) return
        val value = Entry(positionMs, durationMs, System.currentTimeMillis()).encode()
        prefs.edit { keys.forEach { putString(it, value) } }
        prune()
    }

    fun forget(keys: List<String>) {
        if (keys.isEmpty()) return
        prefs.edit { keys.forEach { remove(it) } }
    }

    private fun prune() {
        val all = prefs.all
        if (all.size <= MAX_KEYS) return
        val oldest = all.entries
            .sortedBy { Entry.decode(it.value as? String)?.savedAt ?: 0L }
            .take(all.size - KEEP_KEYS)
            .map { it.key }
        prefs.edit { oldest.forEach { remove(it) } }
    }

    private companion object {
        // Each title takes up to five keys, so this is a few hundred titles.
        const val MAX_KEYS = 2_000
        const val KEEP_KEYS = 1_500
    }
}
