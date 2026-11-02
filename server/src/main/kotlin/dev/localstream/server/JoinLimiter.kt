package dev.localstream.server

/** Global budget cannot be bypassed by changing clientId, IP, or opening another socket.
 * Call under peersLock. Reservations happen before authentication (including resumed JOINs).
 * This bounds online guessing to 10 attempts/minute; an attacker can deny new admissions,
 * but existing media and authenticated signaling sessions are unaffected. */
internal class JoinLimiter(private val now: () -> Long) {
    private val attempts = ArrayDeque<Long>()
    fun acquire(): Boolean {
        val time = now()
        while (attempts.isNotEmpty() && time - attempts.first() >= 60_000) attempts.removeFirst()
        if (attempts.size >= 10) return false
        attempts.addLast(time)
        return true
    }
}
