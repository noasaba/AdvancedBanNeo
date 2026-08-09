package me.leoko.advancedban.network.protocol;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable message metadata and payload. The signature is held by {@link SignedPacket}. */
public final class ProtocolPacket {
    private final int protocolVersion;
    private final UUID sessionId;
    private final long sequence;
    private final long timestampMillis;
    private final String sourceNode;
    private final String targetNode;
    private final MessageKind kind;
    private final byte[] payload;

    public ProtocolPacket(int protocolVersion, UUID sessionId, long sequence, long timestampMillis,
                          String sourceNode, String targetNode, MessageKind kind, byte[] payload) {
        this.protocolVersion = protocolVersion;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.sequence = sequence;
        this.timestampMillis = timestampMillis;
        this.sourceNode = requireNodeId(sourceNode, "sourceNode");
        this.targetNode = requireNodeId(targetNode, "targetNode");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        if (this.payload.length > ProtocolConstants.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload is too large");
        }
    }

    private static String requireNodeId(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return normalized;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public long getSequence() {
        return sequence;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getSourceNode() {
        return sourceNode;
    }

    public String getTargetNode() {
        return targetNode;
    }

    public MessageKind getKind() {
        return kind;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProtocolPacket)) {
            return false;
        }
        ProtocolPacket that = (ProtocolPacket) other;
        return protocolVersion == that.protocolVersion && sequence == that.sequence
                && timestampMillis == that.timestampMillis && sessionId.equals(that.sessionId)
                && sourceNode.equals(that.sourceNode) && targetNode.equals(that.targetNode)
                && kind == that.kind && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(protocolVersion, sessionId, sequence, timestampMillis,
                sourceNode, targetNode, kind);
        return 31 * result + Arrays.hashCode(payload);
    }
}
