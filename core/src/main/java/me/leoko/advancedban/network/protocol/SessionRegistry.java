package me.leoko.advancedban.network.protocol;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks the sole current session per peer and invalidates replaced sessions. */
public final class SessionRegistry implements AutoCloseable {
    private final ConcurrentMap<UUID, ProtocolSession> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> currentByPeer = new ConcurrentHashMap<>();
    private final ProtocolCodec codec = new ProtocolCodec();

    public synchronized void activate(ProtocolSession session) {
        Objects.requireNonNull(session, "session");
        ProtocolSession collision = byId.get(session.getSessionId());
        if (collision != null && collision != session) {
            throw new IllegalArgumentException("session id is already active");
        }
        UUID previousId = currentByPeer.put(session.getPeerNode(), session.getSessionId());
        if (previousId != null && !previousId.equals(session.getSessionId())) {
            ProtocolSession previous = byId.remove(previousId);
            if (previous != null) {
                previous.close();
            }
        }
        ProtocolSession replaced = byId.put(session.getSessionId(), session);
        if (replaced != null && replaced != session) {
            replaced.close();
        }
    }

    public ProtocolPacket authenticate(byte[] encoded, long nowMillis)
            throws ProtocolException, AuthenticationException {
        SignedPacket signed = codec.decode(encoded);
        ProtocolSession session = byId.get(signed.getPacket().getSessionId());
        if (session == null) {
            throw new AuthenticationException(AuthenticationException.Reason.STALE_SESSION);
        }
        return session.open(signed, nowMillis);
    }

    public synchronized void remove(UUID sessionId) {
        ProtocolSession removed = byId.remove(sessionId);
        if (removed != null) {
            currentByPeer.remove(removed.getPeerNode(), sessionId);
            removed.close();
        }
    }

    public int size() {
        return byId.size();
    }

    @Override
    public synchronized void close() {
        for (ProtocolSession session : byId.values()) {
            session.close();
        }
        byId.clear();
        currentByPeer.clear();
    }
}
