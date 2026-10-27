package com.thefactor1.player.home

import com.thefactor1.player.hub.HubItem

/**
 * What the Refine sheet narrows a collection to. Genres and lengths widen the choice (any of them),
 * formats narrow it (all of them), and a sort replaces the hub's own order.
 */
data class Refine(
    val genres: Set<String> = emptySet(),
    val lengths: Set<Int> = emptySet(),
    val formats: Set<Int> = emptySet(),
    /** Index into [SORTS], or -1 for the hub's order. */
    val sort: Int = -1,
) {
    /** How many choices are on, for "FILTER · n" and "CLEAR ALL · n". */
    val count: Int get() = genres.size + lengths.size + formats.size + (if (sort >= 0) 1 else 0)

    fun apply(items: List<HubItem>): List<HubItem> {
        val kept = items.filter { item ->
            (genres.isEmpty() || item.genres.any { it in genres }) &&
                (lengths.isEmpty() || lengths.any { LENGTHS[it].matches(item.runtimeMin) }) &&
                formats.all { FORMATS[it].matches(item) }
        }
        return if (sort in SORTS.indices) kept.sortedWith(SORTS[sort].order) else kept
    }

    /** Titles [genre] would show with the other choices as they are: what its row's count says. */
    fun countFor(genre: String, items: List<HubItem>): Int = copy(genres = setOf(genre)).apply(items).size

    fun toggleGenre(genre: String) = copy(genres = genres.toggle(genre))
    fun toggleLength(index: Int) = copy(lengths = lengths.toggle(index))
    fun toggleFormat(index: Int) = copy(formats = formats.toggle(index))
    fun pickSort(index: Int) = copy(sort = if (sort == index) -1 else index)

    /** One line for SharedPreferences: "Drama,Comedy|0,2|1|0". */
    fun encode(): String = listOf(genres.joinToString(","), lengths.joinToString(","), formats.joinToString(","), sort.toString()).joinToString("|")

    class Length(val label: String, val matches: (Int?) -> Boolean)
    class Format(val label: String, val matches: (HubItem) -> Boolean)
    class Sort(val label: String, val order: Comparator<HubItem>)

    companion object {
        val LENGTHS = listOf(
            Length("< 1 H") { it != null && it < 60 },
            Length("1–2 H") { it != null && it in 60..120 },
            Length("2 H +") { it != null && it > 120 },
        )
        val FORMATS = listOf(
            Format("4K") { item -> item.badges.res.orEmpty().let { "4K" in it.uppercase() || "2160" in it } },
            Format("HDR") { item -> item.badges.hdr.orEmpty().let { it.isNotEmpty() && !it.equals("SDR", ignoreCase = true) } },
            Format("Atmos") { item -> "atmos" in item.badges.audio.orEmpty().lowercase() },
        )
        val SORTS = listOf(
            Sort("Newest", compareByDescending { it.year ?: 0 }),
            Sort("A–Z", compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
            // Unknown lengths go last rather than first.
            Sort("Shortest", compareBy { it.runtimeMin ?: Int.MAX_VALUE }),
        )

        /** Genres present in [items], in alphabetical order. */
        fun genresOf(items: List<HubItem>): List<String> = items.flatMap { it.genres }.distinct().sorted()

        fun decode(text: String?): Refine {
            val parts = text?.split('|') ?: return Refine()
            if (parts.size != 4) return Refine()
            fun ints(part: String) = part.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            return Refine(
                genres = parts[0].split(',').filter { it.isNotEmpty() }.toSet(),
                lengths = ints(parts[1]).filter { it in LENGTHS.indices }.toSet(),
                formats = ints(parts[2]).filter { it in FORMATS.indices }.toSet(),
                sort = parts[3].toIntOrNull()?.takeIf { it in SORTS.indices } ?: -1,
            )
        }

        private fun <T> Set<T>.toggle(value: T) = if (value in this) this - value else this + value
    }
}
