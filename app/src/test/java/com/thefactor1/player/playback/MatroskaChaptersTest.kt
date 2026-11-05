package com.thefactor1.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MatroskaChaptersTest {

    @Test
    fun readsChaptersFromARealFile() {
        // Made by ffmpeg: 10 s of black with three chapters.
        val file = javaClass.getResourceAsStream("/chapters.mkv")!!.readBytes()
        val chapters = MatroskaChapters.read(fetcher(file))
        assertEquals(
            listOf(Chapter(0, "Opening"), Chapter(4_000, "The Heist"), Chapter(7_500, "Fin")),
            chapters,
        )
    }

    @Test
    fun followsTheSeekIndexPastAttachments() {
        val chapters = chaptersElement(edition(atom(0, "One"), atom(600_000, "Two"), atom(1_200_000, null)))
        val file = mkv(padding = 400_000, chapters = chapters)
        val reads = mutableListOf<Long>()
        val result = MatroskaChapters.read { offset, length ->
            reads += offset
            fetcher(file)(offset, length)
        }
        assertEquals(listOf(Chapter(0, "One"), Chapter(600_000, "Two"), Chapter(1_200_000, null)), result)
        assertEquals(2, reads.size)
    }

    @Test
    fun takesTheDefaultEditionAndSkipsHiddenChapters() {
        val chapters = chaptersElement(
            edition(atom(0, "Wrong"), atom(5_000, "Wrong too")),
            edition(atom(0, "Start"), atom(1_000, "Hidden", hidden = true), atom(9_000, "End"), default = true),
        )
        assertEquals(
            listOf(Chapter(0, "Start"), Chapter(9_000, "End")),
            MatroskaChapters.read(fetcher(mkv(padding = 0, chapters = chapters))),
        )
    }

    @Test
    fun oneChapterIsNoChapters() {
        val file = mkv(padding = 0, chapters = chaptersElement(edition(atom(0, "Only"))))
        assertTrue(MatroskaChapters.read(fetcher(file)).isEmpty())
    }

    @Test
    fun notMatroskaReadsOnce() {
        var reads = 0
        val mp4 = ByteArray(4096) { if (it in 4..7) "ftyp"[it - 4].code.toByte() else 0 }
        assertTrue(MatroskaChapters.read { o, l -> reads++; fetcher(mp4)(o, l) }.isEmpty())
        assertEquals(1, reads)
    }

    @Test
    fun aFailedReadIsNoChapters() {
        assertTrue(MatroskaChapters.read { _, _ -> null }.isEmpty())
    }

    // ---- A tiny Matroska writer --------------------------------------------------------------

    private fun fetcher(file: ByteArray): (Long, Int) -> ByteArray? = { offset, length ->
        if (offset >= file.size) null else file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
    }

    private fun mkv(padding: Int, chapters: ByteArray): ByteArray {
        val header = el(0x1A45DFA3, el(0x4282, "matroska".toByteArray()))
        val void = if (padding > 0) el(0xEC, ByteArray(padding)) else ByteArray(0)
        // The index's own size doesn't depend on the position written into it (8 bytes, fixed).
        fun seekHead(position: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x1043A770)) + el(0x53AC, u64(position))))
        val probe = seekHead(0)
        val chaptersPosition = (probe.size + void.size).toLong()
        val body = seekHead(chaptersPosition) + void + chapters + el(0x1F43B675, ByteArray(16))
        return header + el(0x18538067, body, unknownSize = true)
    }

    private fun chaptersElement(vararg editions: ByteArray) = el(0x1043A770, editions.fold(ByteArray(0)) { a, b -> a + b })

    private fun edition(vararg atoms: ByteArray, default: Boolean = false) =
        el(0x45B9, (if (default) el(0x45DB, byteArrayOf(1)) else ByteArray(0)) + atoms.fold(ByteArray(0)) { a, b -> a + b })

    private fun atom(startMs: Long, title: String?, hidden: Boolean = false): ByteArray {
        var body = el(0x91, u64(startMs * 1_000_000))
        if (hidden) body += el(0x98, byteArrayOf(1))
        if (title != null) body += el(0x80, el(0x85, title.toByteArray()) + el(0x437C, "eng".toByteArray()))
        return el(0xB6, body)
    }

    private fun id(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var started = false
        for (shift in 24 downTo 0 step 8) {
            val b = ((value shr shift) and 0xFF).toInt()
            if (b != 0 || started) { out.write(b); started = true }
        }
        return out.toByteArray()
    }

    private fun u64(value: Long) = ByteArray(8) { ((value shr (56 - 8 * it)) and 0xFF).toByte() }

    private fun el(id: Long, body: ByteArray, unknownSize: Boolean = false): ByteArray {
        // Sizes always as 8-byte vints: simple, and legal.
        val size = if (unknownSize) ByteArray(8) { if (it == 0) 0x01 else 0xFF.toByte() }
        else ByteArray(8) { if (it == 0) 0x01 else ((body.size.toLong() shr (56 - 8 * it)) and 0xFF).toByte() }
        return id(id) + size + body
    }
}
