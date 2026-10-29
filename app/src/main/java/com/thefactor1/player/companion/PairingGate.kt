package com.thefactor1.player.companion

import java.security.MessageDigest

/**
 * Who may pair with the TV, and how often they may try. The code exists only while the pairing card
 * is on screen; each wrong guess makes that address wait twice as long as before (from a second),
 * and [MAX_WRONG] wrong guesses in all close pairing until the card is opened again. A phone whose
 * request to connect was turned down (or ignored) waits a minute before it can ask again. Kept free
 * of Android types so it can be unit tested.
 */
class PairingGate(private val now: () -> Long = System::currentTimeMillis, private val newCode: () -> String) {

    enum class Verdict { OK, WRONG, WAIT, CLOSED }

    /** The code a new phone types, or null while no pairing card is up. */
    @Volatile var code: String? = null
        private set

    private val wrong = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()
    private val helloAfter = HashMap<String, Long>()
    private var wrongInAll = 0

    /** The pairing card came up: a fresh code, and a fresh count of wrong guesses. */
    @Synchronized
    fun open(): String {
        val fresh = newCode()
        code = fresh
        wrong.clear()
        lockedUntil.clear()
        wrongInAll = 0
        return fresh
    }

    @Synchronized
    fun close() {
        code = null
    }

    /** [guess] from [ip]: right (and pairing then closes), wrong, too soon after a wrong one, or no pairing open. */
    @Synchronized
    fun attempt(ip: String, guess: String): Verdict {
        val current = code ?: return Verdict.CLOSED
        if (now() < (lockedUntil[ip] ?: 0L)) return Verdict.WAIT
        // Constant-time, so how long a wrong guess takes says nothing about how close it was.
        if (MessageDigest.isEqual(guess.toByteArray(), current.toByteArray())) {
            code = null
            wrong.remove(ip)
            lockedUntil.remove(ip)
            return Verdict.OK
        }
        val count = (wrong[ip] ?: 0) + 1
        wrong[ip] = count
        lockedUntil[ip] = now() + delayAfter(count)
        if (++wrongInAll >= MAX_WRONG) code = null
        return Verdict.WRONG
    }

    /** Whether [ip] may put a "connect this phone?" question on screen now. */
    @Synchronized
    fun mayAsk(ip: String): Boolean = now() >= (helloAfter[ip] ?: 0L)

    /** The TV said no to [ip], or nobody answered: it waits before asking again. */
    @Synchronized
    fun refused(ip: String) {
        helloAfter[ip] = now() + HELLO_COOLDOWN_MS
    }

    companion object {
        const val MAX_WRONG = 10
        const val HELLO_COOLDOWN_MS = 60_000L
        private const val FIRST_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 5L * 60 * 1000

        /** 1 s after the first wrong guess, then 2, 4, 8 … up to five minutes. */
        fun delayAfter(wrongGuesses: Int): Long =
            (FIRST_DELAY_MS shl (wrongGuesses - 1).coerceIn(0, 20)).coerceAtMost(MAX_DELAY_MS)
    }
}

/**
 * Paired phones' tokens as kept on the TV: "token|issued-epoch-ms", the oldest dropped first once
 * there are more than the TV keeps. Tokens from before the issue time was kept count as oldest.
 */
object PhoneTokens {
    fun add(stored: Set<String>, token: String, issuedMs: Long, max: Int): Set<String> =
        (stored + "$token|$issuedMs").sortedBy { issued(it) }.takeLast(max).toSet()

    fun contains(stored: Set<String>, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val candidate = token.toByteArray()
        // Every entry is compared in full, so the answer takes the same time wherever the match is.
        return stored.fold(false) { found, entry -> MessageDigest.isEqual(entry.substringBefore('|').toByteArray(), candidate) || found }
    }

    private fun issued(entry: String): Long = entry.substringAfter('|', "").toLongOrNull() ?: 0L
}
