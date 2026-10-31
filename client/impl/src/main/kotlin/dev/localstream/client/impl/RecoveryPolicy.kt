package dev.localstream.client.impl

import kotlin.math.min

/** Pure policy; network events and media progress are the inputs, not UI lifecycle callbacks. */
internal object RecoveryPolicy {
    fun backoffMs(attempt: Int, jitter: Double): Long {
        val base = min(10_000L, 500L * (1L shl attempt.coerceIn(0, 5)))
        return (base * (0.8 + jitter.coerceIn(0.0, 1.0) * 0.4)).toLong()
    }
    enum class Action { NONE, RESTART_ICE, RECREATE_PEER }
    fun action(now: Long, lastProgress: Long, failed: Boolean, disconnectedSince: Long?,
        stage: Int, deadline: Long, networkAvailable: Boolean): Action {
        if (!networkAvailable) return Action.NONE
        if (stage > 0) return if (now >= deadline) Action.RECREATE_PEER else Action.NONE
        val stalled = now - lastProgress >= 4_000
        val disconnected = disconnectedSince != null && now - disconnectedSince >= 2_000
        return if (failed || stalled || disconnected) Action.RESTART_ICE else Action.NONE
    }
}
