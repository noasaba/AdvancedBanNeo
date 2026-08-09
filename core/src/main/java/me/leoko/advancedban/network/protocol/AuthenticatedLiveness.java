package me.leoko.advancedban.network.protocol;

/**
 * Tracks only authenticated inbound traffic for persistent connection health.
 * Callers should supply a monotonic millisecond clock (for example nanoTime converted
 * to milliseconds), never an unverified packet timestamp.
 */
public final class AuthenticatedLiveness {
    public enum Action {
        NONE,
        SEND_HEARTBEAT,
        CLOSE
    }

    private final long idleBeforeHeartbeatMillis;
    private final long heartbeatDeadlineMillis;
    private long lastAuthenticatedInboundMillis;
    private long heartbeatSentMillis = -1L;

    public AuthenticatedLiveness(long nowMillis, long idleBeforeHeartbeatMillis,
                                 long heartbeatDeadlineMillis) {
        if (idleBeforeHeartbeatMillis <= 0L || heartbeatDeadlineMillis <= 0L) {
            throw new IllegalArgumentException("liveness intervals must be positive");
        }
        this.lastAuthenticatedInboundMillis = nowMillis;
        this.idleBeforeHeartbeatMillis = idleBeforeHeartbeatMillis;
        this.heartbeatDeadlineMillis = heartbeatDeadlineMillis;
    }

    public synchronized void authenticatedInbound(long nowMillis) {
        lastAuthenticatedInboundMillis = nowMillis;
        heartbeatSentMillis = -1L;
    }

    public synchronized Action onReadTimeout(long nowMillis) {
        if (heartbeatSentMillis >= 0L) {
            return elapsed(heartbeatSentMillis, nowMillis) >= heartbeatDeadlineMillis
                    ? Action.CLOSE : Action.NONE;
        }
        if (elapsed(lastAuthenticatedInboundMillis, nowMillis) >= idleBeforeHeartbeatMillis) {
            heartbeatSentMillis = nowMillis;
            return Action.SEND_HEARTBEAT;
        }
        return Action.NONE;
    }

    public synchronized boolean isHeartbeatOutstanding() {
        return heartbeatSentMillis >= 0L;
    }

    private static long elapsed(long earlier, long later) {
        return later <= earlier ? 0L : later - earlier;
    }
}
