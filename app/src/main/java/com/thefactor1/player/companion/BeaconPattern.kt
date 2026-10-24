package com.thefactor1.player.companion

/**
 * The layout of the invisible pairing code: a 9 by 4 grid over the whole picture. The first column
 * is a fixed reference (on, off, on, off), and each row holds four pairs of cells, one on and one
 * off, so every frame has as much light added as taken away. 16 pairs carry a 12-bit code and a
 * 4-bit check. The phone app reads the same layout (phone/BeaconCode.kt), so change both together.
 */
object BeaconPattern {
    const val COLS = 9
    const val ROWS = 4
    const val CODE_BITS = 12

    /** Which cells are "on" for [code] (0 until 4096), row by row. */
    fun cells(code: Int): BooleanArray {
        require(code in 0 until (1 shl CODE_BITS))
        val bits = (code shl 4) or crc4(code)
        val cells = BooleanArray(COLS * ROWS)
        for (row in 0 until ROWS) {
            cells[row * COLS] = row % 2 == 0
            for (pair in 0 until 4) {
                val bit = (bits shr (15 - (row * 4 + pair))) and 1 == 1
                cells[row * COLS + 1 + pair * 2] = bit
                cells[row * COLS + 2 + pair * 2] = !bit
            }
        }
        return cells
    }

    /** CRC-4 (x^4 + x + 1) of a 12-bit code, so a misread almost never passes as a real one. */
    fun crc4(code: Int): Int {
        var crc = 0
        for (i in CODE_BITS - 1 downTo 0) {
            val top = ((crc shr 3) and 1) xor ((code shr i) and 1)
            crc = (crc shl 1) and 0xF
            if (top == 1) crc = crc xor 0x3
        }
        return crc
    }
}
