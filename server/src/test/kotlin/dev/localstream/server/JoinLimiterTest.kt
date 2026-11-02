package dev.localstream.server

import org.junit.Assert.*
import org.junit.Test

class JoinLimiterTest {
    @Test fun globalBudgetIsSharedAcrossConnectionsAndExpiresAtBoundary() {
        var now = 0L
        val limiter = JoinLimiter { now }
        repeat(10) { assertTrue(limiter.acquire()) }
        repeat(1000) { assertFalse(limiter.acquire()) }
        now = 59_999
        assertFalse(limiter.acquire())
        now = 60_000
        repeat(10) { assertTrue(limiter.acquire()) }
        assertFalse(limiter.acquire())
    }

    @Test fun slidingWindowDoesNotRefillAtAnArbitraryMinuteBoundary() {
        var now = 59_000L
        val limiter = JoinLimiter { now }
        repeat(5) { assertTrue(limiter.acquire()) }
        now = 61_000
        repeat(5) { assertTrue(limiter.acquire()) }
        now = 119_000
        repeat(5) { assertTrue(limiter.acquire()) }
        assertFalse(limiter.acquire())
    }
}
