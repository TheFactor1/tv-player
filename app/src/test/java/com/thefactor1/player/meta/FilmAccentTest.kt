package com.thefactor1.player.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmAccentTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = c shr 16 and 0xFF
    private fun green(c: Int) = c shr 8 and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun greyBackdropLendsNoColour() {
        val pixels = IntArray(400) { i -> val v = (i * 37) % 256; argb(v, v, v) }
        assertNull(FilmAccent.pick(pixels))
    }

    @Test
    fun aFewColouredPixelsAreNotEnough() {
        val pixels = IntArray(400) { i -> if (i < 10) argb(220, 40, 40) else argb(90, 90, 90) }
        assertNull(FilmAccent.pick(pixels))
    }

    @Test
    fun orangeHazeGivesASoftOrange() {
        val pixels = IntArray(400) { i -> if (i % 3 == 0) argb(20, 15, 10) else argb(230, 130, 50) }
        val accent = FilmAccent.pick(pixels)
        assertNotNull(accent)
        accent!!
        assertTrue(red(accent) > green(accent) && green(accent) > blue(accent))
        // Toned down: light, and nowhere near fully saturated.
        assertEquals(219, red(accent))
        assertTrue(blue(accent) > 100)
    }

    @Test
    fun dominantHueWinsOverASmallerOne() {
        val pixels = IntArray(400) { i -> if (i < 300) argb(60, 140, 200) else argb(220, 60, 50) }
        val accent = FilmAccent.pick(pixels)!!
        assertTrue(blue(accent) > red(accent))
    }

    @Test
    fun darkPixelsAreIgnored() {
        val pixels = IntArray(400) { i -> if (i < 340) argb(30, 0, 0) else argb(90, 200, 120) }
        val accent = FilmAccent.pick(pixels)!!
        assertTrue(green(accent) > red(accent))
    }
}
