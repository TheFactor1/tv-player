package com.thefactor1.player.resume

import android.content.Context
import androidx.core.content.edit

/**
 * [TitleChoices] kept on the device, written under every key of a title so another stream of it
 * finds them, and read back by the first key that has some. The oldest go once there are more
 * than [MAX_KEYS].
 */
class TitleMemory(context: Context) {
    private val prefs = context.getSharedPreferences("title_choices", Context.MODE_PRIVATE)

    fun find(keys: List<String>): TitleChoices? =
        keys.firstNotNullOfOrNull { TitleChoices.decode(prefs.getString(it, null)) }

    /** Changes what is kept for [keys], starting from what is there now. */
    fun update(keys: List<String>, change: (TitleChoices) -> TitleChoices) {
        if (keys.isEmpty()) return
        val value = change(find(keys) ?: TitleChoices()).copy(savedAt = System.currentTimeMillis()).encode()
        prefs.edit { keys.forEach { putString(it, value) } }
        prune()
    }

    private fun prune() {
        val all = prefs.all
        if (all.size <= MAX_KEYS) return
        val oldest = all.entries
            .sortedBy { TitleChoices.decode(it.value as? String)?.savedAt ?: 0L }
            .take(all.size - KEEP_KEYS)
            .map { it.key }
        prefs.edit { oldest.forEach { remove(it) } }
    }

    private companion object {
        const val MAX_KEYS = 2_000
        const val KEEP_KEYS = 1_500
    }
}
