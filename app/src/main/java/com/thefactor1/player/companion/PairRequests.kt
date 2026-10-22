package com.thefactor1.player.companion

/**
 * One-tap pairing for the phone app: a phone asks, the TV shows who's asking, and OK lets it in.
 * One request at a time, so a stray phone can't pile cards up on screen; an unanswered request
 * lapses after a minute. Kept free of Android types so it can be unit tested.
 */
class PairRequests(private val now: () -> Long = System::currentTimeMillis) {

    enum class Status { PENDING, APPROVED, DENIED, EXPIRED, UNKNOWN }

    class Answer(val status: Status, val token: String? = null)

    private class Entry(val id: String, val created: Long) {
        var status = Status.PENDING
        var token: String? = null
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** False while another request is still waiting for an answer. */
    @Synchronized
    fun open(id: String): Boolean {
        expire()
        if (entries.values.any { it.status == Status.PENDING }) return false
        entries[id] = Entry(id, now())
        while (entries.size > KEEP) entries.remove(entries.keys.first())
        return true
    }

    /** Records the TV's answer; [token] is what an approved phone gets. */
    @Synchronized
    fun answer(id: String, approved: Boolean, token: String?) {
        expire()
        val entry = entries[id]?.takeIf { it.status == Status.PENDING } ?: return
        entry.status = if (approved) Status.APPROVED else Status.DENIED
        entry.token = token.takeIf { approved }
    }

    /** What the phone sees when it checks back. The token is handed over once. */
    @Synchronized
    fun check(id: String): Answer {
        expire()
        val entry = entries[id] ?: return Answer(Status.UNKNOWN)
        if (entry.status == Status.APPROVED) {
            val token = entry.token
            entries.remove(id)
            return Answer(Status.APPROVED, token)
        }
        return Answer(entry.status)
    }

    private fun expire() {
        val cutoff = now() - TIMEOUT_MS
        entries.values.filter { it.status == Status.PENDING && it.created < cutoff }.forEach { it.status = Status.EXPIRED }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        private const val KEEP = 8
    }
}
