package com.thefactor1.player.resume

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The films and shows played lately, newest first, for the home screen. One entry per title: a
 * show keeps only the episode watched last. [ResumeStore] keeps the positions playback resumes
 * from; this keeps what the home screen needs to show them (name, art key, the app to go back to).
 */
class WatchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    data class Entry(
        /** Groups a show's episodes and a film's different streams under one entry. */
        val key: String,
        val name: String,
        /** As the release name gave it, for the art lookup: the name searched and its year. */
        val searchName: String?,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val imdbId: String?,
        /** "2024  ·  2 h 46 m  ·  Science Fiction, Adventure" */
        val kicker: String?,
        /** "S1 E1  ·  Pilot" */
        val episodeLine: String?,
        val description: String?,
        val positionMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        /** The app that opened the stream, so Continue can go back to it. */
        val sourcePackage: String?,
        val savedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key)
            put("name", name)
            putOpt("searchName", searchName)
            putOpt("year", year)
            putOpt("season", season)
            putOpt("episode", episode)
            putOpt("imdbId", imdbId)
            putOpt("kicker", kicker)
            putOpt("episodeLine", episodeLine)
            putOpt("description", description)
            put("position", positionMs)
            put("duration", durationMs)
            put("watched", watched)
            putOpt("source", sourcePackage)
            put("savedAt", savedAt)
        }

        companion object {
            fun from(json: JSONObject): Entry? {
                val key = json.optString("key").ifEmpty { return null }
                val name = json.optString("name").ifEmpty { return null }
                return Entry(
                    key = key,
                    name = name,
                    searchName = json.text("searchName"),
                    year = json.int("year"),
                    season = json.int("season"),
                    episode = json.int("episode"),
                    imdbId = json.text("imdbId"),
                    kicker = json.text("kicker"),
                    episodeLine = json.text("episodeLine"),
                    description = json.text("description"),
                    positionMs = json.optLong("position"),
                    durationMs = json.optLong("duration"),
                    watched = json.optBoolean("watched"),
                    sourcePackage = json.text("source"),
                    savedAt = json.optLong("savedAt"),
                )
            }

            private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
            private fun JSONObject.int(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null
        }
    }

    fun all(): List<Entry> {
        val array = try {
            JSONArray(prefs.getString(KEY, "[]"))
        } catch (e: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Entry::from) }
    }

    /** Puts [entry] first, replacing the one with the same key; what it doesn't know is kept from that one. */
    fun record(entry: Entry) {
        val existing = all()
        val previous = existing.firstOrNull { it.key == entry.key }
        val sameEpisode = previous != null && previous.season == entry.season && previous.episode == entry.episode
        val merged = if (previous == null) entry else entry.copy(
            imdbId = entry.imdbId ?: previous.imdbId,
            kicker = entry.kicker ?: previous.kicker,
            episodeLine = entry.episodeLine ?: previous.episodeLine.takeIf { sameEpisode },
            description = entry.description ?: previous.description.takeIf { sameEpisode },
            sourcePackage = entry.sourcePackage ?: previous.sourcePackage,
        )
        val kept = listOf(merged) + existing.filter { it.key != entry.key }.take(MAX_ENTRIES - 1)
        prefs.edit { putString(KEY, JSONArray(kept.map { it.toJson() }).toString()) }
    }

    private companion object {
        const val KEY = "entries"
        const val MAX_ENTRIES = 20
    }
}
