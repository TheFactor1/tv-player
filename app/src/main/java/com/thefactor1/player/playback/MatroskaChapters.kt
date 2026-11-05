package com.thefactor1.player.playback

/** One chapter: where it starts and its name, if the file gives one. */
data class Chapter(val startMs: Long, val title: String?)

/**
 * Reads the chapters of a Matroska (MKV) file, which Media3 skips. Only two small pieces of the
 * file are needed: its head, whose seek index says where the chapters are (or which holds them),
 * and then the chapters element itself.
 */
object MatroskaChapters {
    /** What the head of a file says about its chapters. */
    sealed class Head {
        /** The chapters were in the bytes read. */
        data class Found(val chapters: List<Chapter>) : Head()
        /** The chapters, or a second seek index, start at [offset] from the start of the file. */
        data class Elsewhere(val offset: Long, val isSeekHead: Boolean) : Head()
        /** Not Matroska, or no chapters in it. */
        data object None : Head()
    }

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_EDITION = 0x45B9L
    private const val ID_EDITION_HIDDEN = 0x45BDL
    private const val ID_EDITION_DEFAULT = 0x45DBL
    private const val ID_EDITION_ORDERED = 0x45DDL
    private const val ID_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_FLAG_HIDDEN = 0x98L
    private const val ID_FLAG_ENABLED = 0x4598L
    private const val ID_DISPLAY = 0x80L
    private const val ID_STRING = 0x85L

    private const val UNKNOWN_SIZE = -1L

    /** An element's id, how big its content is, and where the content starts. */
    private class Element(val id: Long, val size: Long, val dataStart: Int) {
        val dataEnd: Long get() = if (size == UNKNOWN_SIZE) Long.MAX_VALUE else dataStart + size
    }

    /** How much of the file's start is read: enough for the seek index and what usually follows it. */
    const val HEAD_BYTES = 256 * 1024
    /** How much is read where the index points: chapters are a few kilobytes even with many names. */
    const val CHUNK_BYTES = 128 * 1024

    /**
     * The file's chapters, reading it through [fetch] (offset, length → at most that many bytes
     * from there, or null when the read fails). At most three reads; an empty list when the file
     * isn't Matroska, has no chapters, or can't be read.
     */
    fun read(fetch: (offset: Long, length: Int) -> ByteArray?): List<Chapter> {
        val head = fetch(0L, HEAD_BYTES) ?: return emptyList()
        val segmentStart = segmentStart(head) ?: return emptyList()
        var result = readHead(head)
        repeat(2) {
            when (val r = result) {
                is Head.Found -> return r.chapters
                Head.None -> return emptyList()
                is Head.Elsewhere -> {
                    val bytes = fetch(r.offset, CHUNK_BYTES) ?: return emptyList()
                    result = readAt(bytes, segmentStart, r.offset)
                }
            }
        }
        return (result as? Head.Found)?.chapters.orEmpty()
    }

    /** Reads [bytes], the first bytes of a file. Offsets returned count from the start of the file. */
    fun readHead(bytes: ByteArray): Head {
        val start = segmentStart(bytes) ?: return Head.None
        return readSegment(bytes, start, from = start.toInt(), bytesOffset = 0L)
    }

    /** Reads [bytes] that begin [bytesOffset] into the file, at a chapters element or a seek index found through [readHead]. */
    fun readAt(bytes: ByteArray, segmentStart: Long, bytesOffset: Long): Head {
        val el = element(bytes, 0) ?: return Head.None
        return when (el.id) {
            ID_CHAPTERS -> if (el.dataEnd <= bytes.size) Head.Found(readChapters(bytes, el)) else Head.None
            // Only one hop: a second index pointing to a third is not followed.
            ID_SEEK_HEAD -> readSegment(bytes, segmentStart, from = 0, bytesOffset = bytesOffset)
                .let { if (it is Head.Elsewhere && it.isSeekHead) Head.None else it }
            else -> Head.None
        }
    }

    /** Where the segment's content starts in the file, from its first bytes; seek positions count from here. */
    fun segmentStart(bytes: ByteArray): Long? {
        val ebml = element(bytes, 0) ?: return null
        if (ebml.id != ID_EBML || ebml.dataEnd > bytes.size) return null
        val segment = element(bytes, ebml.dataEnd.toInt()) ?: return null
        return if (segment.id == ID_SEGMENT) segment.dataStart.toLong() else null
    }

    /**
     * Reads a segment's top-level elements from [from] in [bytes], which start [bytesOffset] into
     * the file; the segment's content starts at [segmentStart] in the file.
     */
    private fun readSegment(bytes: ByteArray, segmentStart: Long, from: Int, bytesOffset: Long): Head {
        var pos = from
        var chaptersAt: Long? = null
        var nextSeekHeadAt: Long? = null
        while (pos < bytes.size) {
            val el = element(bytes, pos) ?: break
            when (el.id) {
                ID_CHAPTERS -> {
                    if (el.dataEnd > bytes.size) return Head.Elsewhere(bytesOffset + pos, isSeekHead = false)
                    return Head.Found(readChapters(bytes, el))
                }
                ID_SEEK_HEAD -> if (el.dataEnd <= bytes.size) {
                    for ((id, position) in seekEntries(bytes, el)) {
                        val inFile = segmentStart + position
                        when (id) {
                            ID_CHAPTERS -> chaptersAt = inFile
                            // A second index, usually at the end, listing what the first doesn't.
                            ID_SEEK_HEAD -> if (inFile > bytesOffset + pos) nextSeekHeadAt = inFile
                        }
                    }
                }
                // Past the head: the picture starts here, and chapters, if any, are listed in an index.
                ID_CLUSTER -> break
            }
            if (el.size == UNKNOWN_SIZE || el.dataEnd > bytes.size) break
            pos = el.dataEnd.toInt()
        }
        chaptersAt?.let { return Head.Elsewhere(it, isSeekHead = false) }
        nextSeekHeadAt?.let { return Head.Elsewhere(it, isSeekHead = true) }
        return Head.None
    }

    private fun seekEntries(bytes: ByteArray, seekHead: Element): List<Pair<Long, Long>> {
        val entries = mutableListOf<Pair<Long, Long>>()
        children(bytes, seekHead) { seek ->
            if (seek.id != ID_SEEK) return@children
            var id: Long? = null
            var position: Long? = null
            children(bytes, seek) { field ->
                when (field.id) {
                    ID_SEEK_ID -> id = uint(bytes, field)
                    ID_SEEK_POSITION -> position = uint(bytes, field)
                }
            }
            val i = id
            val p = position
            if (i != null && p != null) entries += i to p
        }
        return entries
    }

    private class Edition(val chapters: List<Chapter>, val isDefault: Boolean, val hidden: Boolean, val ordered: Boolean)

    private fun readChapters(bytes: ByteArray, chapters: Element): List<Chapter> {
        val editions = mutableListOf<Edition>()
        children(bytes, chapters) { edition ->
            if (edition.id != ID_EDITION) return@children
            var isDefault = false
            var hidden = false
            var ordered = false
            val list = mutableListOf<Chapter>()
            children(bytes, edition) { child ->
                when (child.id) {
                    ID_EDITION_DEFAULT -> isDefault = uint(bytes, child) == 1L
                    ID_EDITION_HIDDEN -> hidden = uint(bytes, child) == 1L
                    ID_EDITION_ORDERED -> ordered = uint(bytes, child) == 1L
                    ID_ATOM -> readAtom(bytes, child)?.let { list += it }
                }
            }
            editions += Edition(list, isDefault, hidden, ordered)
        }
        // An ordered edition plays pieces in its own order, which this timeline can't follow.
        val usable = editions.filter { !it.ordered && it.chapters.isNotEmpty() }
        val edition = usable.firstOrNull { it.isDefault && !it.hidden } ?: usable.firstOrNull { !it.hidden } ?: return emptyList()
        val sorted = edition.chapters.sortedBy { it.startMs }.distinctBy { it.startMs }
        // One chapter, or all at the start, says nothing about where anything is.
        return if (sorted.size < 2) emptyList() else sorted
    }

    private fun readAtom(bytes: ByteArray, atom: Element): Chapter? {
        var startNs: Long? = null
        var hidden = false
        var enabled = true
        var title: String? = null
        children(bytes, atom) { child ->
            when (child.id) {
                ID_TIME_START -> startNs = uint(bytes, child)
                ID_FLAG_HIDDEN -> hidden = uint(bytes, child) == 1L
                ID_FLAG_ENABLED -> enabled = uint(bytes, child) != 0L
                ID_DISPLAY -> if (title == null) children(bytes, child) { display ->
                    if (display.id == ID_STRING && title == null) {
                        title = String(bytes, display.dataStart, display.size.toInt(), Charsets.UTF_8).trim('\u0000', ' ').ifEmpty { null }
                    }
                }
            }
        }
        val start = startNs ?: return null
        if (hidden || !enabled) return null
        return Chapter(start / 1_000_000L, title)
    }

    /** Calls [each] for every child of [parent] that lies wholly in [bytes]. */
    private inline fun children(bytes: ByteArray, parent: Element, each: (Element) -> Unit) {
        var pos = parent.dataStart
        val end = minOf(parent.dataEnd, bytes.size.toLong()).toInt()
        while (pos < end) {
            val el = element(bytes, pos) ?: return
            if (el.size == UNKNOWN_SIZE || el.dataEnd > end) return
            each(el)
            pos = el.dataEnd.toInt()
        }
    }

    private fun element(bytes: ByteArray, pos: Int): Element? {
        val idLength = vintLength(bytes, pos) ?: return null
        if (idLength > 4 || pos + idLength > bytes.size) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (bytes[pos + i].toLong() and 0xFF)
        val sizePos = pos + idLength
        val sizeLength = vintLength(bytes, sizePos) ?: return null
        if (sizePos + sizeLength > bytes.size) return null
        var size = (bytes[sizePos].toLong() and 0xFF) and (0xFF shr sizeLength).toLong()
        var allOnes = size == (0xFF shr sizeLength).toLong()
        for (i in 1 until sizeLength) {
            val b = bytes[sizePos + i].toLong() and 0xFF
            if (b != 0xFFL) allOnes = false
            size = (size shl 8) or b
        }
        return Element(id, if (allOnes) UNKNOWN_SIZE else size, sizePos + sizeLength)
    }

    private fun vintLength(bytes: ByteArray, pos: Int): Int? {
        if (pos >= bytes.size) return null
        val first = bytes[pos].toInt() and 0xFF
        if (first == 0) return null
        return Integer.numberOfLeadingZeros(first) - 24 + 1
    }

    private fun uint(bytes: ByteArray, el: Element): Long {
        var value = 0L
        for (i in 0 until el.size.toInt().coerceAtMost(8)) value = (value shl 8) or (bytes[el.dataStart + i].toLong() and 0xFF)
        return value
    }
}
