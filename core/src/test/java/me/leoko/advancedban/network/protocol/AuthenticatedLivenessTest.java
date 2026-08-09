package me.leoko.advancedban.network.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatedLivenessTest {
    @Test
    void silentHalfOpenConnectionIsPingedThenClosedAtDeadline() {
        AuthenticatedLiveness liveness = new AuthenticatedLiveness(1_000L, 100L, 50L);

        assertEquals(AuthenticatedLiveness.Action.NONE, liveness.onReadTimeout(1_099L));
        assertEquals(AuthenticatedLiveness.Action.SEND_HEARTBEAT, liveness.onReadTimeout(1_100L));
        assertTrue(liveness.isHeartbeatOutstanding());
        assertEquals(AuthenticatedLiveness.Action.NONE, liveness.onReadTimeout(1_149L));
        assertEquals(AuthenticatedLiveness.Action.CLOSE, liveness.onReadTimeout(1_150L));
    }

    @Test
    void onlyAuthenticatedInboundClearsOutstandingHeartbeat() {
        AuthenticatedLiveness liveness = new AuthenticatedLiveness(0L, 100L, 50L);
        assertEquals(AuthenticatedLiveness.Action.SEND_HEARTBEAT, liveness.onReadTimeout(100L));

        liveness.authenticatedInbound(120L);

        assertFalse(liveness.isHeartbeatOutstanding());
        assertEquals(AuthenticatedLiveness.Action.NONE, liveness.onReadTimeout(219L));
        assertEquals(AuthenticatedLiveness.Action.SEND_HEARTBEAT, liveness.onReadTimeout(220L));
    }

    @Test
    void backwardClockMovementCannotPrematurelyCloseConnection() {
        AuthenticatedLiveness liveness = new AuthenticatedLiveness(1_000L, 100L, 50L);
        assertEquals(AuthenticatedLiveness.Action.NONE, liveness.onReadTimeout(900L));
    }
}
