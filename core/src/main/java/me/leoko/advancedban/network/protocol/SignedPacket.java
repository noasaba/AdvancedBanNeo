package me.leoko.advancedban.network.protocol;

import java.util.Objects;

/** A protocol packet and its HMAC-SHA256 authentication tag. */
public final class SignedPacket {
    private final ProtocolPacket packet;
    private final byte[] authenticationTag;

    public SignedPacket(ProtocolPacket packet, byte[] authenticationTag) {
        this.packet = Objects.requireNonNull(packet, "packet");
        this.authenticationTag = Objects.requireNonNull(authenticationTag, "authenticationTag").clone();
        if (this.authenticationTag.length != ProtocolConstants.HMAC_SIZE_BYTES) {
            throw new IllegalArgumentException("authenticationTag must be 32 bytes");
        }
    }

    public ProtocolPacket getPacket() {
        return packet;
    }

    public byte[] getAuthenticationTag() {
        return authenticationTag.clone();
    }
}
