package dev.localstream.client.impl

import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun mediaProgressProtectsHealthyVideoEvenWhenControlChannelReconnects() {
        assertEquals(RecoveryPolicy.Action.NONE, RecoveryPolicy.action(10_000, 9_900, false, null, 0, 0, true))
    }
    @Test fun frozenVideoWithConnectedTransportTriggersRecovery() {
        assertEquals(RecoveryPolicy.Action.RESTART_ICE, RecoveryPolicy.action(10_000, 5_000, false, null, 0, 0, true))
    }
    @Test fun shortDisconnectionDoesNotDestroyPeer() {
        assertEquals(RecoveryPolicy.Action.NONE, RecoveryPolicy.action(10_000, 9_000, false, 9_500, 0, 0, true))
        assertEquals(RecoveryPolicy.Action.RESTART_ICE, RecoveryPolicy.action(12_000, 9_000, false, 9_500, 0, 0, true))
    }
    @Test fun noNetworkWaitsForCallbackInsteadOfRestartingIce() {
        assertEquals(RecoveryPolicy.Action.NONE, RecoveryPolicy.action(30_000, 0, true, 0, 1, 20_000, false))
    }
    @Test fun failedTransportRestartsImmediatelyThenEscalatesAfterDeadline() {
        assertEquals(RecoveryPolicy.Action.RESTART_ICE, RecoveryPolicy.action(100, 100, true, null, 0, 0, true))
        assertEquals(RecoveryPolicy.Action.NONE, RecoveryPolicy.action(9_999, 0, true, null, 1, 10_000, true))
        assertEquals(RecoveryPolicy.Action.RECREATE_PEER, RecoveryPolicy.action(10_000, 0, true, null, 1, 10_000, true))
    }
    @Test fun backoffHasJitterAndStaysBoundedEvenAfterLongOutage() {
        assertEquals(500, RecoveryPolicy.backoffMs(0, 0.5).toInt())
        assertEquals(1_000, RecoveryPolicy.backoffMs(1, 0.5).toInt())
        assertTrue(RecoveryPolicy.backoffMs(100_000, 0.0) >= 8_000)
        assertTrue(RecoveryPolicy.backoffMs(100_000, 1.0) <= 12_000)
        assertTrue(RecoveryPolicy.backoffMs(3, 0.0) < RecoveryPolicy.backoffMs(3, 1.0))
    }
}
