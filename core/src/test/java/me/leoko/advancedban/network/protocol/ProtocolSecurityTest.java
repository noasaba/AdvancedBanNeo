package me.leoko.advancedban.network.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolSecurityTest {
    private static final long NOW = 10_000_000L;
    private static final byte[] KEY = key((byte) 7);
    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void validAuthenticatedPacketRoundTrips() throws Exception {
        Pair pair = pair(KEY);
        SignedPacket outbound = pair.agent.seal(MessageKind.HEARTBEAT, bytes("healthy"), NOW);
        SignedPacket decoded = codec.decode(codec.encode(outbound));

        ProtocolPacket accepted = pair.authority.open(decoded, NOW + 1);

        assertEquals(MessageKind.HEARTBEAT, accepted.getKind());
        assertArrayEquals(bytes("healthy"), accepted.getPayload());
        assertEquals("paper-1", accepted.getSourceNode());
        assertEquals("velocity", accepted.getTargetNode());
    }

    @Test
    void everyRegisteredMessageKindHasAStableAuthenticatedWireId() throws Exception {
        for (MessageKind kind : MessageKind.values()) {
            Pair pair = pair(KEY);
            ProtocolPacket accepted = pair.authority.open(
                    codec.decode(codec.encode(pair.agent.seal(kind, new byte[0], NOW))), NOW);
            assertEquals(kind, accepted.getKind());
        }
    }

    @Test
    void wrongCredentialAndModifiedPayloadAreRejected() {
        Pair pair = pair(KEY);
        SignedPacket original = pair.agent.seal(MessageKind.APPLY, bytes("original"), NOW);
        ProtocolSession wrongCredentialReceiver = receiver(original.getPacket().getSessionId(), key((byte) 8));

        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> wrongCredentialReceiver.open(original, NOW));

        ProtocolPacket originalPacket = original.getPacket();
        ProtocolPacket modified = copy(originalPacket, originalPacket.getProtocolVersion(),
                originalPacket.getSourceNode(), originalPacket.getTargetNode(), bytes("modified"));
        SignedPacket tampered = new SignedPacket(modified, original.getAuthenticationTag());
        assertReason(AuthenticationException.Reason.BAD_SIGNATURE, () -> pair.authority.open(tampered, NOW));
    }

    @Test
    void signedSourceTargetAndVersionSpoofingAreRejected() {
        Pair pair = pair(KEY);
        ProtocolPacket base = pair.agent.seal(MessageKind.APPLY, bytes("p"), NOW).getPacket();

        SignedPacket sourceSpoof = sign(copy(base, base.getProtocolVersion(), "paper-2", "velocity", bytes("p")), KEY);
        assertReason(AuthenticationException.Reason.SOURCE_MISMATCH,
                () -> pair.authority.open(sourceSpoof, NOW));

        SignedPacket targetSpoof = sign(copy(base, base.getProtocolVersion(), "paper-1", "other-proxy", bytes("p")), KEY);
        assertReason(AuthenticationException.Reason.TARGET_MISMATCH,
                () -> pair.authority.open(targetSpoof, NOW));

        SignedPacket incompatible = sign(copy(base, ProtocolConstants.PROTOCOL_VERSION + 1,
                "paper-1", "velocity", bytes("p")), KEY);
        assertReason(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL,
                () -> pair.authority.open(incompatible, NOW));
    }

    @Test
    void replayDuplicateAndStaleSequenceAreRejected() throws Exception {
        Pair pair = pair(KEY);
        SignedPacket first = pair.agent.seal(MessageKind.HEARTBEAT, new byte[0], NOW);
        pair.authority.open(first, NOW);

        assertReason(AuthenticationException.Reason.STALE_SEQUENCE,
                () -> pair.authority.open(first, NOW));

        SignedPacket second = pair.agent.seal(MessageKind.HEARTBEAT, new byte[0], NOW + 1);
        pair.authority.open(second, NOW + 1);
        assertReason(AuthenticationException.Reason.STALE_SEQUENCE,
                () -> pair.authority.open(first, NOW + 1));
    }

    @Test
    void replacingPeerSessionInvalidatesOldSession() throws Exception {
        UUID firstId = UUID.randomUUID();
        ProtocolSession firstSender = sender(firstId, KEY);
        ProtocolSession firstReceiver = receiver(firstId, KEY);
        SessionRegistry registry = new SessionRegistry();
        registry.activate(firstReceiver);

        UUID secondId = UUID.randomUUID();
        ProtocolSession secondSender = sender(secondId, KEY);
        registry.activate(receiver(secondId, KEY));

        assertEquals(1, registry.size());
        assertReason(AuthenticationException.Reason.STALE_SESSION,
                () -> registry.authenticate(codec.encode(firstSender.seal(MessageKind.HEARTBEAT,
                        new byte[0], NOW)), NOW));

        ProtocolPacket accepted = registry.authenticate(codec.encode(secondSender.seal(
                MessageKind.HEARTBEAT, new byte[0], NOW)), NOW);
        assertEquals(secondId, accepted.getSessionId());
    }

    @Test
    void timestampAndSessionExpiryAreEnforced() {
        Pair pair = pair(KEY);
        SignedPacket future = pair.agent.seal(MessageKind.HEARTBEAT, new byte[0], NOW + 40_000L);
        assertReason(AuthenticationException.Reason.INVALID_TIMESTAMP,
                () -> pair.authority.open(future, NOW));

        Pair stalePair = pair(KEY);
        SignedPacket stale = stalePair.agent.seal(MessageKind.HEARTBEAT, new byte[0], NOW);
        assertReason(AuthenticationException.Reason.INVALID_TIMESTAMP,
                () -> stalePair.authority.open(stale, NOW + 40_000L));

        SignedPacket valid = pair.agent.seal(MessageKind.HEARTBEAT, new byte[0], NOW);
        assertReason(AuthenticationException.Reason.SESSION_EXPIRED,
                () -> pair.authority.open(valid, NOW + 120_001L));
    }

    @Test
    void sessionKeyDerivationBindsEverySessionInput() {
        byte[] credential = key((byte) 3);
        UUID session = UUID.randomUUID();
        byte[] agentNonce = new byte[24];
        byte[] authorityNonce = new byte[24];
        Arrays.fill(agentNonce, (byte) 1);
        Arrays.fill(authorityNonce, (byte) 2);

        byte[] derived = SessionKeyDerivation.derive(credential, session, "paper-1", "velocity",
                agentNonce, authorityNonce);
        byte[] same = SessionKeyDerivation.derive(credential, session, "paper-1", "velocity",
                agentNonce, authorityNonce);
        authorityNonce[0] = 4;
        byte[] different = SessionKeyDerivation.derive(credential, session, "paper-1", "velocity",
                agentNonce, authorityNonce);

        assertArrayEquals(derived, same);
        assertFalse(Arrays.equals(derived, different));
        assertEquals(ProtocolConstants.HMAC_SIZE_BYTES, derived.length);
        assertThrows(IllegalArgumentException.class, () -> SessionKeyDerivation.derive(
                new byte[8], session, "paper-1", "velocity", agentNonce, authorityNonce));
    }

    @Test
    void codecRejectsTruncationTrailingDataAndOversizedNodeIds() {
        SignedPacket signed = sender(UUID.randomUUID(), KEY).seal(MessageKind.ACK, new byte[0], NOW);
        byte[] encoded = codec.encode(signed);
        assertThrows(ProtocolException.class,
                () -> codec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(ProtocolException.class, () -> codec.decode(trailing));

        char[] chars = new char[ProtocolConstants.MAX_NODE_ID_BYTES + 1];
        Arrays.fill(chars, 'a');
        ProtocolPacket invalidNode = new ProtocolPacket(1, UUID.randomUUID(), 1, NOW,
                new String(chars), "velocity", MessageKind.ACK, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> codec.encodeUnsigned(invalidNode));
    }

    @Test
    void payloadAndAuthenticationTagsAreDefensivelyCopied() {
        byte[] payload = bytes("safe");
        ProtocolPacket packet = new ProtocolPacket(1, UUID.randomUUID(), 1, NOW,
                "paper-1", "velocity", MessageKind.ACK, payload);
        payload[0] = 0;
        assertArrayEquals(bytes("safe"), packet.getPayload());
        byte[] returned = packet.getPayload();
        returned[0] = 0;
        assertArrayEquals(bytes("safe"), packet.getPayload());
    }

    private Pair pair(byte[] key) {
        UUID sessionId = UUID.randomUUID();
        return new Pair(sender(sessionId, key), receiver(sessionId, key));
    }

    private ProtocolSession sender(UUID sessionId, byte[] key) {
        return new ProtocolSession(sessionId, "paper-1", "velocity", NOW - 1_000L,
                NOW + 120_000L, 30_000L, key);
    }

    private ProtocolSession receiver(UUID sessionId, byte[] key) {
        return new ProtocolSession(sessionId, "velocity", "paper-1", NOW - 1_000L,
                NOW + 120_000L, 30_000L, key);
    }

    private SignedPacket sign(ProtocolPacket packet, byte[] key) {
        return new SignedPacket(packet, HmacSha256.sign(key, codec.encodeUnsigned(packet)));
    }

    private ProtocolPacket copy(ProtocolPacket packet, int version, String source, String target, byte[] payload) {
        return new ProtocolPacket(version, packet.getSessionId(), packet.getSequence(),
                packet.getTimestampMillis(), source, target, packet.getKind(), payload);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] key(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private void assertReason(AuthenticationException.Reason reason, ThrowingOperation operation) {
        AuthenticationException exception = assertThrows(AuthenticationException.class, operation::run);
        assertEquals(reason, exception.getReason());
    }

    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class Pair {
        private final ProtocolSession agent;
        private final ProtocolSession authority;

        private Pair(ProtocolSession agent, ProtocolSession authority) {
            this.agent = agent;
            this.authority = authority;
        }
    }
}
