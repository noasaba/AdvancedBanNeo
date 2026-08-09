package me.leoko.advancedban.network.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Credential-authenticated handshake used before a session starts. */
public final class HandshakeProtocol {
    private static final int CLIENT_MAGIC = 0x41424E48; // ABNH
    private static final int SERVER_MAGIC = 0x41424E41; // ABNA
    private static final int CHALLENGE_MAGIC = 0x41424E43; // ABNC
    private static final int NONCE_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Creates the server-first, single-connection challenge used by the production transport.
     * The challenge is intentionally not authenticated on its own: it is included in both
     * authenticated hello MACs, so an intermediary can only cause the handshake to fail.
     */
    public ServerChallenge createServerChallenge(String authorityNode, long issuedAtMillis) {
        return new ServerChallenge(ProtocolConstants.PROTOCOL_VERSION, authorityNode,
                issuedAtMillis, randomNonce());
    }

    public byte[] encode(ServerChallenge challenge) {
        return encodeChallenge(challenge.protocolVersion, challenge.authorityNode,
                challenge.issuedAtMillis, challenge.nonce);
    }

    public ServerChallenge decodeServerChallenge(byte[] encoded, long nowMillis,
                                                  long permittedClockSkewMillis)
            throws ProtocolException, AuthenticationException {
        try {
            DataInputStream in = input(encoded);
            if (in.readInt() != CHALLENGE_MAGIC) {
                throw new ProtocolException("invalid server challenge magic");
            }
            int version = in.readInt();
            if (version != ProtocolConstants.PROTOCOL_VERSION) {
                throw new AuthenticationException(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL);
            }
            long issuedAtMillis = in.readLong();
            String authorityNode = readString(in);
            byte[] nonce = readFixed(in, NONCE_BYTES);
            requireEnd(in);
            requireFresh(issuedAtMillis, nowMillis, permittedClockSkewMillis);
            return new ServerChallenge(version, authorityNode, issuedAtMillis, nonce);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new ProtocolException("truncated server challenge", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException("invalid server challenge", exception);
        }
    }

    public ClientHello createClientHello(String nodeId, long timestampMillis, byte[] credential) {
        byte[] nonce = randomNonce();
        byte[] unsigned = encodeClientUnsigned(nodeId, timestampMillis, nonce);
        return new ClientHello(nodeId, timestampMillis, nonce, HmacSha256.sign(credential, unsigned));
    }

    /** Creates a client hello cryptographically bound to the current server challenge. */
    public ClientHello createClientHello(String nodeId, long timestampMillis,
                                         ServerChallenge challenge, byte[] credential) {
        Objects.requireNonNull(challenge, "challenge");
        byte[] nonce = randomNonce();
        byte[] unsigned = encodeClientUnsigned(nodeId, timestampMillis, nonce);
        return new ClientHello(nodeId, timestampMillis, nonce,
                HmacSha256.sign(credential, appendContext(unsigned, encode(challenge))));
    }

    public ClientHello decodeAndVerifyClient(byte[] encoded, byte[] credential)
            throws ProtocolException, AuthenticationException {
        ClientHello hello = decodeClient(encoded);
        if (!HmacSha256.verify(credential,
                encodeClientUnsigned(hello.nodeId, hello.timestampMillis, hello.nonce), hello.tag)) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        return hello;
    }

    /** Verifies the credential and enforces the caller's handshake freshness window. */
    public ClientHello decodeAndVerifyClient(byte[] encoded, byte[] credential, long nowMillis,
                                             long permittedClockSkewMillis)
            throws ProtocolException, AuthenticationException {
        ClientHello hello = decodeAndVerifyClient(encoded, credential);
        requireFresh(hello.timestampMillis, nowMillis, permittedClockSkewMillis);
        return hello;
    }

    /** Verifies a client hello against the unique challenge issued on this socket. */
    public ClientHello decodeAndVerifyClient(byte[] encoded, byte[] credential,
                                             ServerChallenge challenge, long nowMillis,
                                             long permittedClockSkewMillis)
            throws ProtocolException, AuthenticationException {
        Objects.requireNonNull(challenge, "challenge");
        ClientHello hello = decodeClient(encoded);
        if (!HmacSha256.verify(credential,
                appendContext(encodeClientUnsigned(hello.nodeId, hello.timestampMillis, hello.nonce),
                        encode(challenge)), hello.tag)) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        requireFresh(hello.timestampMillis, nowMillis, permittedClockSkewMillis);
        return hello;
    }

    public ServerHello createServerHello(ClientHello client, String authorityNode, long issuedAtMillis,
                                         long expiresAtMillis, byte[] credential) {
        if (expiresAtMillis <= issuedAtMillis) {
            throw new IllegalArgumentException("session expiry must be after issue time");
        }
        UUID sessionId = UUID.randomUUID();
        byte[] authorityNonce = randomNonce();
        byte[] unsigned = encodeServerAuthenticationInput(true, ProtocolConstants.PROTOCOL_VERSION, sessionId,
                authorityNode, issuedAtMillis, expiresAtMillis, authorityNonce, client.nonce);
        return new ServerHello(true, ProtocolConstants.PROTOCOL_VERSION, sessionId, authorityNode,
                issuedAtMillis, expiresAtMillis, authorityNonce,
                HmacSha256.sign(credential, unsigned));
    }

    /** Creates a server response bound to the complete server-first handshake transcript. */
    public ServerHello createServerHello(ClientHello client, ServerChallenge challenge,
                                         long issuedAtMillis, long expiresAtMillis,
                                         byte[] credential) {
        Objects.requireNonNull(challenge, "challenge");
        if (expiresAtMillis <= issuedAtMillis) {
            throw new IllegalArgumentException("session expiry must be after issue time");
        }
        UUID sessionId = UUID.randomUUID();
        byte[] authorityNonce = randomNonce();
        byte[] unsigned = encodeServerAuthenticationInput(true, ProtocolConstants.PROTOCOL_VERSION, sessionId,
                challenge.authorityNode, issuedAtMillis, expiresAtMillis, authorityNonce, client.nonce);
        return new ServerHello(true, ProtocolConstants.PROTOCOL_VERSION, sessionId, challenge.authorityNode,
                issuedAtMillis, expiresAtMillis, authorityNonce,
                HmacSha256.sign(credential, appendContext(unsigned, encode(challenge))));
    }

    public ServerHello decodeAndVerifyServer(byte[] encoded, byte[] clientNonce, byte[] credential)
            throws ProtocolException, AuthenticationException {
        ServerHello hello = decodeServer(encoded);
        if (!hello.accepted) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        byte[] unsigned = encodeServerAuthenticationInput(hello.accepted, hello.protocolVersion, hello.sessionId,
                hello.authorityNode, hello.issuedAtMillis, hello.expiresAtMillis,
                hello.authorityNonce, clientNonce);
        if (!HmacSha256.verify(credential, unsigned, hello.tag)) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        if (hello.protocolVersion != ProtocolConstants.PROTOCOL_VERSION) {
            throw new AuthenticationException(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL);
        }
        return hello;
    }

    /** Verifies the response, freshness, and that the offered session has not expired. */
    public ServerHello decodeAndVerifyServer(byte[] encoded, byte[] clientNonce, byte[] credential,
                                             long nowMillis, long permittedClockSkewMillis)
            throws ProtocolException, AuthenticationException {
        ServerHello hello = decodeAndVerifyServer(encoded, clientNonce, credential);
        requireFresh(hello.issuedAtMillis, nowMillis, permittedClockSkewMillis);
        if (hello.expiresAtMillis <= hello.issuedAtMillis || hello.expiresAtMillis <= nowMillis) {
            throw new AuthenticationException(AuthenticationException.Reason.SESSION_EXPIRED);
        }
        return hello;
    }

    /** Verifies the response and its binding to the server challenge seen by this client. */
    public ServerHello decodeAndVerifyServer(byte[] encoded, byte[] clientNonce,
                                             ServerChallenge challenge, byte[] credential,
                                             long nowMillis, long permittedClockSkewMillis)
            throws ProtocolException, AuthenticationException {
        Objects.requireNonNull(challenge, "challenge");
        ServerHello hello = decodeServer(encoded);
        if (!hello.accepted) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        byte[] unsigned = encodeServerAuthenticationInput(hello.accepted, hello.protocolVersion, hello.sessionId,
                hello.authorityNode, hello.issuedAtMillis, hello.expiresAtMillis,
                hello.authorityNonce, clientNonce);
        if (!HmacSha256.verify(credential, appendContext(unsigned, encode(challenge)), hello.tag)) {
            throw new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE);
        }
        if (hello.protocolVersion != ProtocolConstants.PROTOCOL_VERSION) {
            throw new AuthenticationException(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL);
        }
        if (!challenge.authorityNode.equals(hello.authorityNode)) {
            throw new AuthenticationException(AuthenticationException.Reason.SOURCE_MISMATCH);
        }
        requireFresh(hello.issuedAtMillis, nowMillis, permittedClockSkewMillis);
        if (hello.expiresAtMillis <= hello.issuedAtMillis || hello.expiresAtMillis <= nowMillis) {
            throw new AuthenticationException(AuthenticationException.Reason.SESSION_EXPIRED);
        }
        return hello;
    }

    public byte[] encode(ClientHello hello) {
        return appendTag(encodeClientUnsigned(hello.nodeId, hello.timestampMillis, hello.nonce), hello.tag);
    }

    public byte[] encode(ServerHello hello, byte[] clientNonce) {
        return appendTag(encodeServerWireUnsigned(hello.accepted, hello.protocolVersion, hello.sessionId,
                hello.authorityNode, hello.issuedAtMillis, hello.expiresAtMillis,
                hello.authorityNonce), hello.tag);
    }

    private ClientHello decodeClient(byte[] encoded) throws ProtocolException, AuthenticationException {
        try {
            DataInputStream in = input(encoded);
            if (in.readInt() != CLIENT_MAGIC) {
                throw new ProtocolException("invalid client handshake magic");
            }
            int version = in.readInt();
            if (version != ProtocolConstants.PROTOCOL_VERSION) {
                throw new AuthenticationException(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL);
            }
            long timestamp = in.readLong();
            String nodeId = readString(in);
            byte[] nonce = readFixed(in, NONCE_BYTES);
            byte[] tag = readFixed(in, ProtocolConstants.HMAC_SIZE_BYTES);
            requireEnd(in);
            return new ClientHello(nodeId, timestamp, nonce, tag);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new ProtocolException("truncated client handshake", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException("invalid client handshake", exception);
        }
    }

    private ServerHello decodeServer(byte[] encoded) throws ProtocolException {
        try {
            DataInputStream in = input(encoded);
            if (in.readInt() != SERVER_MAGIC) {
                throw new ProtocolException("invalid server handshake magic");
            }
            boolean accepted = in.readBoolean();
            int version = in.readInt();
            UUID sessionId = new UUID(in.readLong(), in.readLong());
            String authorityNode = readString(in);
            long issued = in.readLong();
            long expires = in.readLong();
            byte[] nonce = readFixed(in, NONCE_BYTES);
            byte[] tag = readFixed(in, ProtocolConstants.HMAC_SIZE_BYTES);
            requireEnd(in);
            return new ServerHello(accepted, version, sessionId, authorityNode, issued, expires, nonce, tag);
        } catch (EOFException exception) {
            throw new ProtocolException("truncated server handshake", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new ProtocolException("invalid server handshake", exception);
        }
    }

    private byte[] encodeClientUnsigned(String nodeId, long timestampMillis, byte[] nonce) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(CLIENT_MAGIC);
            out.writeInt(ProtocolConstants.PROTOCOL_VERSION);
            out.writeLong(timestampMillis);
            writeString(out, nodeId);
            writeFixed(out, nonce, NONCE_BYTES, "client nonce");
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory handshake encoding failed", impossible);
        }
    }

    private byte[] encodeServerWireUnsigned(boolean accepted, int version, UUID sessionId,
                                            String authorityNode, long issuedAtMillis, long expiresAtMillis,
                                            byte[] authorityNonce) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(SERVER_MAGIC);
            out.writeBoolean(accepted);
            out.writeInt(version);
            out.writeLong(sessionId.getMostSignificantBits());
            out.writeLong(sessionId.getLeastSignificantBits());
            writeString(out, authorityNode);
            out.writeLong(issuedAtMillis);
            out.writeLong(expiresAtMillis);
            writeFixed(out, authorityNonce, NONCE_BYTES, "authority nonce");
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory handshake encoding failed", impossible);
        }
    }

    private byte[] encodeServerAuthenticationInput(boolean accepted, int version, UUID sessionId,
                                                   String authorityNode, long issuedAtMillis, long expiresAtMillis,
                                                   byte[] authorityNonce, byte[] clientNonce) {
        byte[] wire = encodeServerWireUnsigned(accepted, version, sessionId, authorityNode,
                issuedAtMillis, expiresAtMillis, authorityNonce);
        if (clientNonce == null || clientNonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("client nonce has invalid length");
        }
        byte[] authenticated = Arrays.copyOf(wire, wire.length + clientNonce.length);
        System.arraycopy(clientNonce, 0, authenticated, wire.length, clientNonce.length);
        return authenticated;
    }

    private byte[] appendTag(byte[] unsigned, byte[] tag) {
        if (tag == null || tag.length != ProtocolConstants.HMAC_SIZE_BYTES) {
            throw new IllegalArgumentException("invalid handshake tag");
        }
        byte[] result = Arrays.copyOf(unsigned, unsigned.length + tag.length);
        System.arraycopy(tag, 0, result, unsigned.length, tag.length);
        return result;
    }

    private static byte[] appendContext(byte[] message, byte[] context) {
        byte[] result = Arrays.copyOf(message, message.length + context.length);
        System.arraycopy(context, 0, result, message.length, context.length);
        return result;
    }

    private byte[] encodeChallenge(int version, String authorityNode, long issuedAtMillis, byte[] nonce) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(CHALLENGE_MAGIC);
            out.writeInt(version);
            out.writeLong(issuedAtMillis);
            writeString(out, authorityNode);
            writeFixed(out, nonce, NONCE_BYTES, "server challenge nonce");
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory challenge encoding failed", impossible);
        }
    }

    private static DataInputStream input(byte[] encoded) throws ProtocolException {
        if (encoded == null || encoded.length == 0 || encoded.length > 4096) {
            throw new ProtocolException("invalid handshake size");
        }
        return new DataInputStream(new ByteArrayInputStream(encoded));
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "nodeId").getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > ProtocolConstants.MAX_NODE_ID_BYTES) {
            throw new IllegalArgumentException("invalid node id length");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException, ProtocolException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > ProtocolConstants.MAX_NODE_ID_BYTES) {
            throw new ProtocolException("invalid node id length");
        }
        return new String(readFixed(in, length), StandardCharsets.UTF_8);
    }

    private static void writeFixed(DataOutputStream out, byte[] value, int length, String name)
            throws IOException {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " has invalid length");
        }
        out.write(value);
    }

    private static byte[] readFixed(DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void requireEnd(DataInputStream in) throws IOException, ProtocolException {
        if (in.available() != 0) {
            throw new ProtocolException("trailing handshake data");
        }
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static void requireFresh(long timestampMillis, long nowMillis, long permittedClockSkewMillis)
            throws AuthenticationException {
        if (permittedClockSkewMillis < 0) {
            throw new IllegalArgumentException("clock skew must not be negative");
        }
        long earliest = nowMillis < Long.MIN_VALUE + permittedClockSkewMillis
                ? Long.MIN_VALUE : nowMillis - permittedClockSkewMillis;
        long latest = nowMillis > Long.MAX_VALUE - permittedClockSkewMillis
                ? Long.MAX_VALUE : nowMillis + permittedClockSkewMillis;
        if (timestampMillis < earliest || timestampMillis > latest) {
            throw new AuthenticationException(AuthenticationException.Reason.INVALID_TIMESTAMP);
        }
    }

    public static final class ClientHello {
        private final String nodeId;
        private final long timestampMillis;
        private final byte[] nonce;
        private final byte[] tag;

        private ClientHello(String nodeId, long timestampMillis, byte[] nonce, byte[] tag) {
            this.nodeId = nodeId;
            this.timestampMillis = timestampMillis;
            this.nonce = nonce.clone();
            this.tag = tag.clone();
        }

        public String getNodeId() { return nodeId; }
        public long getTimestampMillis() { return timestampMillis; }
        public byte[] getNonce() { return nonce.clone(); }
    }

    public static final class ServerChallenge {
        private final int protocolVersion;
        private final String authorityNode;
        private final long issuedAtMillis;
        private final byte[] nonce;

        private ServerChallenge(int protocolVersion, String authorityNode, long issuedAtMillis, byte[] nonce) {
            this.protocolVersion = protocolVersion;
            this.authorityNode = authorityNode;
            this.issuedAtMillis = issuedAtMillis;
            this.nonce = nonce.clone();
        }

        public String getAuthorityNode() { return authorityNode; }
        public long getIssuedAtMillis() { return issuedAtMillis; }
        public byte[] getNonce() { return nonce.clone(); }
    }

    public static final class ServerHello {
        private final boolean accepted;
        private final int protocolVersion;
        private final UUID sessionId;
        private final String authorityNode;
        private final long issuedAtMillis;
        private final long expiresAtMillis;
        private final byte[] authorityNonce;
        private final byte[] tag;

        private ServerHello(boolean accepted, int protocolVersion, UUID sessionId, String authorityNode,
                            long issuedAtMillis, long expiresAtMillis, byte[] authorityNonce, byte[] tag) {
            this.accepted = accepted;
            this.protocolVersion = protocolVersion;
            this.sessionId = sessionId;
            this.authorityNode = authorityNode;
            this.issuedAtMillis = issuedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.authorityNonce = authorityNonce.clone();
            this.tag = tag.clone();
        }

        public UUID getSessionId() { return sessionId; }
        public String getAuthorityNode() { return authorityNode; }
        public long getIssuedAtMillis() { return issuedAtMillis; }
        public long getExpiresAtMillis() { return expiresAtMillis; }
        public byte[] getAuthorityNonce() { return authorityNonce.clone(); }
    }
}
