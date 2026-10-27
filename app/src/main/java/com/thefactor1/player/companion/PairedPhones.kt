package com.thefactor1.player.companion

/** A phone that's allowed in: its token, the name it gave, and when it paired. */
data class PairedPhone(val token: String, val name: String, val since: Long)

/**
 * The paired phones as one stored string, a line per phone (token, name, time), so the TV's
 * settings can list them by name and remove one. Newest last; the oldest go past [MAX].
 */
object PairedPhones {
    const val MAX = 8

    fun parse(text: String?): List<PairedPhone> =
        text.orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            val token = parts[0].trim()
            if (token.isEmpty()) return@mapNotNull null
            PairedPhone(token, parts.getOrNull(1)?.ifBlank { null } ?: UNNAMED, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
        }

    fun format(phones: List<PairedPhone>): String =
        phones.joinToString("\n") { "${it.token}\t${clean(it.name)}\t${it.since}" }

    fun add(phones: List<PairedPhone>, phone: PairedPhone): List<PairedPhone> =
        (phones.filter { it.token != phone.token } + phone.copy(name = clean(phone.name))).takeLast(MAX)

    /** Tokens saved before phones had names. */
    fun fromTokens(tokens: Set<String>): List<PairedPhone> = tokens.filter { it.isNotBlank() }.map { PairedPhone(it, UNNAMED, 0L) }

    private fun clean(name: String) = name.replace(Regex("""[\t\r\n]+"""), " ").trim().ifEmpty { UNNAMED }

    const val UNNAMED = "A phone"
}

/**
 * Keeps the invisible code from being guessed: a few wrong answers end it, and for a while after
 * that a phone that isn't paired can't start it again. 3 tries at a 12-bit code per half minute.
 */
class BeaconGuard(private val clock: () -> Long = System::currentTimeMillis) {
    private var misses = 0
    private var lockedUntil = 0L

    fun canStart(): Boolean = clock() >= lockedUntil

    /** A wrong code; true when that was the last allowed, so the code must end now. */
    fun miss(): Boolean {
        if (++misses < MAX_MISSES) return false
        misses = 0
        lockedUntil = clock() + LOCK_MS
        return true
    }

    fun reset() {
        misses = 0
    }

    companion object {
        const val MAX_MISSES = 3
        const val LOCK_MS = 30_000L
    }
}
