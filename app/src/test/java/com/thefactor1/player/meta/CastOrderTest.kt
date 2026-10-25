package com.thefactor1.player.meta

import org.junit.Assert.assertEquals
import org.junit.Test

class CastOrderTest {

    private fun member(name: String, order: Int) = CastMember(name, null, null, order)

    @Test
    fun regularsComeInBillingOrder() {
        val cast = CastOrder.billing(listOf(member("B", 1), member("A", 0), member("C", 2)), emptyList(), 8)
        assertEquals(listOf("A", "B", "C"), cast.map { it.name })
    }

    @Test
    fun guestStarsFollowTheRegulars() {
        val cast = CastOrder.billing(listOf(member("Lead", 0)), listOf(member("Guest", 0)), 8)
        assertEquals(listOf("Lead", "Guest"), cast.map { it.name })
    }

    @Test
    fun someoneCreditedTwiceShowsOnce() {
        val cast = CastOrder.billing(listOf(member("Lead", 0)), listOf(member("Lead", 3), member("Guest", 4)), 8)
        assertEquals(listOf("Lead", "Guest"), cast.map { it.name })
    }

    @Test
    fun keepsToTheLimit() {
        val cast = CastOrder.billing((0 until 20).map { member("P$it", it) }, emptyList(), 8)
        assertEquals(8, cast.size)
        assertEquals("P7", cast.last().name)
    }

    @Test
    fun titleMustMatchTmdbsName() {
        assertEquals(true, CastOrder.sameTitle(listOf("Lanterns", ""), "Lanterns"))
        assertEquals(true, CastOrder.sameTitle(listOf("DC's Lanterns"), "Lanterns"))
        assertEquals(true, CastOrder.sameTitle(listOf("Spider-Man", "Spider-Man"), "Spider Man"))
        assertEquals(false, CastOrder.sameTitle(listOf("Carrie", "Carrie"), "Lanterns"))
        assertEquals(true, CastOrder.sameTitle(listOf("Carrie"), null))
        assertEquals(true, CastOrder.sameTitle(listOf("Ready or Not 2: Here I Come"), "Ready or Not: Here I Come"))
        assertEquals(true, CastOrder.sameTitle(listOf("Ready or Not: Here I Come"), "Ready or Not, Here I Come"))
        assertEquals(false, CastOrder.sameTitle(listOf("Ready or Not"), "Here I Come Again"))
    }
}
