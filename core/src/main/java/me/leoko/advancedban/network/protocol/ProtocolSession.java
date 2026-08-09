package me.leoko.advancedban.network.protocol;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** A direction-aware authenticated session. One instance is used at each endpoint. */
public final class ProtocolSession implements AutoCloseable {
    private final UUID sessionId;
    private final String localNode;
    private final String peerNode;
    private final long issuedAtMillis;
    private final long expiresAtMillis;
    private final long permittedClockSkewMillis;
    private final byte[] sessionKey;
    private final AtomicLong outboundSequence = new AtomicLong(0L);
    private final ReplayGuard inboundReplayGuard = new ReplayGuard();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final ProtocolCodec codec = new ProtocolCodec();

    public ProtocolSession(UUID sessionId, String localNode, String peerNode, long issuedAtMillis,
                           long expiresAtMillis, byte[] sessionKey) {
        this(sessionId, localNode, peerNode, issuedAtMillis, expiresAtMillis,
                ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS, sessionKey);
    }

    public ProtocolSession(UUID sessionId, String localNode, String peerNode, long issuedAtMillis,
                           long expiresAtMillis, long permittedClockSkewMillis, byte[] sessionKey) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.localNode = requireNode(localNode, "localNode");
        this.peerNode = requireNode(peerNode, "peerNode");
        if (expiresAtMillis <= issuedAtMillis) {
            throw new IllegalArgumentException("session expiry must be after issue time");
        }
        if (permittedClockSkewMillis < 0) {
            throw new IllegalArgumentException("clock skew must not be negative");
        }
        HmacSha256.requireKey(sessionKey);
        this.issuedAtMillis = issuedAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.permittedClockSkewMillis = permittedClockSkewMillis;
        this.sessionKey = sessionKey.clone();
    }

    public SignedPacket seal(MessageKind kind, byte[] payload, long nowMillis) {
        if (!active.get() || nowMillis > expiresAtMillis) {
            throw new IllegalStateException("session is not active");
        }
        long sequence = outboundSequence.incrementAndGet();
        if (sequence <= 0) {
            close();
            throw new IllegalStateException("session sequence exhausted");
        }
        ProtocolPacket packet = new ProtocolPacket(ProtocolConstants.PROTOCOL_VERSION, sessionId, sequence,
                nowMillis, localNode, peerNode, kind, payload);
        return new SignedPacket(packet, HmacSha256.sign(sessionKey, codec.encodeUnsigned(packet)));
    }

    public ProtocolPacket open(SignedPacket signedPacket, long nowMillis) throws AuthenticationException {
        ProtocolPacket packet = Objects.requireNonNull(signedPacket, "signedPacket").getPacket();
        if (!active.get() || !sessionId.equals(packet.getSessionId())) {
            throw new AuthenticationException(AuthenticationException.Reason.STALE_SESSION);
        }
        if (!HmacSha256.verify(sessionKey, codec.encodeUnsigned(packet), signedPacket.getAuthenticationTag())) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        if (packet.getProtocolVersion() != ProtocolConstants.PROTOCOL_VERSION) {
            throw new AuthenticationException(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL);
        }
        if (!peerNode.equals(packet.getSourceNode())) {
            throw new AuthenticationException(AuthenticationException.Reason.SOURCE_MISMATCH);
        }
        if (!localNode.equals(packet.getTargetNode())) {
            throw new AuthenticationException(AuthenticationException.Reason.TARGET_MISMATCH);
        }
        if (nowMillis > expiresAtMillis) {
            close();
            throw new AuthenticationException(AuthenticationException.Reason.SESSION_EXPIRED);
        }
        long earliest = Math.max(issuedAtMillis - permittedClockSkewMillis,
                nowMillis - permittedClockSkewMillis);
        long latest = nowMillis + permittedClockSkewMillis;
        if (packet.getTimestampMillis() < earliest || packet.getTimestampMillis() > latest) {
            throw new AuthenticationException(AuthenticationException.Reason.INVALID_TIMESTAMP);
        }
        if (!inboundReplayGuard.accept(packet.getSequence())) {
            throw new AuthenticationException(AuthenticationException.Reason.STALE_SEQUENCE);
        }
        return packet;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getLocalNode() {
        return localNode;
    }

    public String getPeerNode() {
        return peerNode;
    }

    public boolean isActive(long nowMillis) {
        return active.get() && nowMillis <= expiresAtMillis;
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            Arrays.fill(sessionKey, (byte) 0);
        }
    }

    private static String requireNode(String node, String name) {
        Objects.requireNonNull(node, name);
        String normalized = node.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return normalized;
    }
}
