package com.thefactor1.player.phone

/**
 * The TV's invisible pairing code, as the TV lays it out (app/companion/BeaconPattern.kt): a 9 by 4
 * grid, a reference column (on, off, on, off), and four on/off pairs per row carrying a 12-bit code
 * and a 4-bit check. Change both sides together.
 */
object BeaconCode {
    const val COLS = 9
    const val ROWS = 4
    const val PAIRS = 16
    const val CODE_BITS = 12

    fun cells(code: Int): BooleanArray {
        val bits = (code shl 4) or crc4(code)
        val cells = BooleanArray(COLS * ROWS)
        for (row in 0 until ROWS) {
            cells[row * COLS] = row % 2 == 0
            for (pair in 0 until 4) {
                val bit = (bits shr (15 - (row * 4 + pair))) and 1 == 1
                cells[pairCell(row * 4 + pair, 0)] = bit
                cells[pairCell(row * 4 + pair, 1)] = !bit
            }
        }
        return cells
    }

    /** The cell index of pair [pair]'s first (side 0) or second (side 1) cell. */
    fun pairCell(pair: Int, side: Int) = (pair / 4) * COLS + 1 + (pair % 4) * 2 + side

    /** The 16 bits read off the pairs, MSB first: the code when its check agrees, else null. */
    fun code(bits: Int): Int? {
        val code = bits shr 4
        return code.takeIf { crc4(it) == bits and 0xF }
    }

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
