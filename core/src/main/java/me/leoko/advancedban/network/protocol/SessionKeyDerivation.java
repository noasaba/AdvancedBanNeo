package me.leoko.advancedban.network.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Derives a session-specific key from an external network credential and two fresh nonces. */
public final class SessionKeyDerivation {
    private static final byte[] CONTEXT = "AdvancedBanNeo/session/v1".getBytes(StandardCharsets.UTF_8);

    private SessionKeyDerivation() {
    }

    public static byte[] derive(byte[] networkCredential, UUID sessionId, String agentNode,
                                String authorityNode, byte[] agentNonce, byte[] authorityNonce) {
        HmacSha256.requireKey(networkCredential);
        Objects.requireNonNull(sessionId, "sessionId");
        requireNonce(agentNonce, "agentNonce");
        requireNonce(authorityNonce, "authorityNonce");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(CONTEXT.length);
            out.write(CONTEXT);
            out.writeLong(sessionId.getMostSignificantBits());
            out.writeLong(sessionId.getLeastSignificantBits());
            writeString(out, agentNode);
            writeString(out, authorityNode);
            writeBytes(out, agentNonce);
            writeBytes(out, authorityNonce);
            out.flush();
            return HmacSha256.sign(networkCredential, bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory key derivation failed", impossible);
        }
    }

    private static void requireNonce(byte[] nonce, String name) {
        Objects.requireNonNull(nonce, name);
        if (nonce.length < 16) {
            throw new IllegalArgumentException(name + " must contain at least 16 bytes");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        Objects.requireNonNull(value, "node");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > ProtocolConstants.MAX_NODE_ID_BYTES) {
            throw new IllegalArgumentException("invalid node id length");
        }
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(Arrays.copyOf(value, value.length));
    }
}
