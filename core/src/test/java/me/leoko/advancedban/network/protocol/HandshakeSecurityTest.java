package me.leoko.advancedban.network.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeSecurityTest {
    private static final long NOW = 20_000_000L;
    private static final byte[] CREDENTIAL = key((byte) 4);
    private final HandshakeProtocol protocol = new HandshakeProtocol();

    @Test
    void validHandshakeAuthenticatesBothDirectionsAndDerivesSameSessionKey() throws Exception {
        HandshakeProtocol.ClientHello client = protocol.createClientHello("paper-1", NOW, CREDENTIAL);
        HandshakeProtocol.ClientHello acceptedClient = protocol.decodeAndVerifyClient(
                protocol.encode(client), CREDENTIAL, NOW + 1L, 5_000L);
        HandshakeProtocol.ServerHello server = protocol.createServerHello(
                acceptedClient, "velocity", NOW + 1L, NOW + 60_000L, CREDENTIAL);
        byte[] encodedServer = protocol.encode(server, client.getNonce());
        HandshakeProtocol.ServerHello acceptedServer = protocol.decodeAndVerifyServer(
                encodedServer, client.getNonce(), CREDENTIAL, NOW + 2L, 5_000L);

        assertEquals("paper-1", acceptedClient.getNodeId());
        assertEquals("velocity", acceptedServer.getAuthorityNode());
        assertEquals(server.getSessionId(), acceptedServer.getSessionId());
        assertArrayEquals(SessionKeyDerivation.derive(CREDENTIAL, server.getSessionId(), "paper-1", "velocity",
                        client.getNonce(), server.getAuthorityNonce()),
                SessionKeyDerivation.derive(CREDENTIAL, acceptedServer.getSessionId(), "paper-1", "velocity",
                        acceptedClient.getNonce(), acceptedServer.getAuthorityNonce()));
    }

    @Test
    void productionHandshakeIsBoundToServerFirstChallenge() throws Exception {
        HandshakeProtocol.ServerChallenge challenge = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ServerChallenge acceptedChallenge = protocol.decodeServerChallenge(
                protocol.encode(challenge), NOW + 1L, 5_000L);
        HandshakeProtocol.ClientHello client = protocol.createClientHello(
                "paper-1", NOW + 1L, acceptedChallenge, CREDENTIAL);
        HandshakeProtocol.ClientHello acceptedClient = protocol.decodeAndVerifyClient(
                protocol.encode(client), CREDENTIAL, challenge, NOW + 2L, 5_000L);
        HandshakeProtocol.ServerHello server = protocol.createServerHello(
                acceptedClient, challenge, NOW + 2L, NOW + 60_000L, CREDENTIAL);
        HandshakeProtocol.ServerHello acceptedServer = protocol.decodeAndVerifyServer(
                protocol.encode(server, client.getNonce()), client.getNonce(), acceptedChallenge,
                CREDENTIAL, NOW + 3L, 5_000L);

        assertEquals("paper-1", acceptedClient.getNodeId());
        assertEquals("velocity", acceptedServer.getAuthorityNode());
    }

    @Test
    void capturedClientHelloCannotBeReplayedAfterAuthorityRestart() throws Exception {
        HandshakeProtocol.ServerChallenge beforeRestart = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ClientHello captured = protocol.createClientHello(
                "paper-1", NOW, beforeRestart, CREDENTIAL);

        // A restarted Authority has an empty in-memory replay guard, but always issues
        // a new unpredictable challenge before it accepts a credential proof.
        HandshakeProtocol restartedAuthority = new HandshakeProtocol();
        HandshakeProtocol.ServerChallenge afterRestart = restartedAuthority.createServerChallenge(
                "velocity", NOW + 1L);
        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> restartedAuthority.decodeAndVerifyClient(protocol.encode(captured), CREDENTIAL,
                        afterRestart, NOW + 1L, 5_000L));
    }

    @Test
    void clientAndServerHellosCannotCrossChallengeTranscripts() throws Exception {
        HandshakeProtocol.ServerChallenge first = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ServerChallenge second = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ClientHello client = protocol.createClientHello("paper-1", NOW, first, CREDENTIAL);

        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> protocol.decodeAndVerifyClient(protocol.encode(client), CREDENTIAL,
                        second, NOW, 1_000L));

        HandshakeProtocol.ServerHello response = protocol.createServerHello(
                client, first, NOW, NOW + 60_000L, CREDENTIAL);
        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> protocol.decodeAndVerifyServer(protocol.encode(response, client.getNonce()),
                        client.getNonce(), second, CREDENTIAL, NOW, 1_000L));
    }

    @Test
    void staleOrIncompatibleServerChallengeIsRejected() throws Exception {
        HandshakeProtocol.ServerChallenge stale = protocol.createServerChallenge("velocity", NOW - 10_000L);
        assertReason(AuthenticationException.Reason.INVALID_TIMESTAMP,
                () -> protocol.decodeServerChallenge(protocol.encode(stale), NOW, 1_000L));

        byte[] incompatible = protocol.encode(protocol.createServerChallenge("velocity", NOW));
        // CHALLENGE_MAGIC occupies bytes 0..3 and protocol version bytes 4..7.
        incompatible[7] ^= 1;
        assertReason(AuthenticationException.Reason.INCOMPATIBLE_PROTOCOL,
                () -> protocol.decodeServerChallenge(incompatible, NOW, 1_000L));
    }

    @Test
    void wrongCredentialAndTamperingAreRejected() {
        HandshakeProtocol.ClientHello hello = protocol.createClientHello("paper-1", NOW, CREDENTIAL);
        byte[] encoded = protocol.encode(hello);

        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> protocol.decodeAndVerifyClient(encoded, key((byte) 9), NOW, 1_000L));

        byte[] tampered = encoded.clone();
        tampered[20] ^= 1;
        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> protocol.decodeAndVerifyClient(tampered, CREDENTIAL, NOW, 1_000L));
    }

    @Test
    void serverResponseIsBoundToTheOriginatingClientNonce() throws Exception {
        HandshakeProtocol.ClientHello client = protocol.createClientHello("paper-1", NOW, CREDENTIAL);
        HandshakeProtocol.ServerHello server = protocol.createServerHello(
                client, "velocity", NOW, NOW + 60_000L, CREDENTIAL);
        byte[] encoded = protocol.encode(server, client.getNonce());
        byte[] otherNonce = client.getNonce();
        otherNonce[0] ^= 1;

        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> protocol.decodeAndVerifyServer(encoded, otherNonce, CREDENTIAL, NOW, 1_000L));
    }

    @Test
    void handshakeTimestampsAndExpiredOffersAreRejected() throws Exception {
        HandshakeProtocol.ClientHello old = protocol.createClientHello("paper-1", NOW - 10_000L, CREDENTIAL);
        assertReason(AuthenticationException.Reason.INVALID_TIMESTAMP,
                () -> protocol.decodeAndVerifyClient(protocol.encode(old), CREDENTIAL, NOW, 1_000L));

        HandshakeProtocol.ClientHello client = protocol.createClientHello("paper-1", NOW - 2_000L, CREDENTIAL);
        HandshakeProtocol.ServerHello staleServer = protocol.createServerHello(
                client, "velocity", NOW - 2_000L, NOW - 1_000L, CREDENTIAL);
        assertReason(AuthenticationException.Reason.INVALID_TIMESTAMP,
                () -> protocol.decodeAndVerifyServer(protocol.encode(staleServer, client.getNonce()),
                        client.getNonce(), CREDENTIAL, NOW, 1_000L));

        HandshakeProtocol.ServerHello expiredServer = protocol.createServerHello(
                client, "velocity", NOW - 500L, NOW, CREDENTIAL);
        assertReason(AuthenticationException.Reason.SESSION_EXPIRED,
                () -> protocol.decodeAndVerifyServer(protocol.encode(expiredServer, client.getNonce()),
                        client.getNonce(), CREDENTIAL, NOW, 1_000L));
    }

    @Test
    void replayGuardRejectsDuplicatesButPermitsNonceAfterRetention() {
        HandshakeReplayGuard guard = new HandshakeReplayGuard();
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 2);

        assertTrue(guard.accept("paper-1", nonce, NOW, 1_000L));
        assertFalse(guard.accept("paper-1", nonce, NOW + 999L, 1_000L));
        assertTrue(guard.accept("paper-1", nonce, NOW + 1_000L, 1_000L));
        assertFalse(guard.accept("paper-2", nonce, NOW + 1_001L, 1_000L),
                "changing a non-secret Agent identity must not bypass replay detection");
    }

    @Test
    void sharedKeySupportsDistinctInternalAgentUuidsWithoutCrossSessionTrust() throws Exception {
        UUID firstIdentity = AgentIdentity.generate();
        UUID secondIdentity = AgentIdentity.generate();
        assertFalse(firstIdentity.equals(secondIdentity));

        HandshakeProtocol.ServerChallenge firstChallenge = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ServerChallenge secondChallenge = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ClientHello first = protocol.createClientHello(
                firstIdentity, NOW, firstChallenge, CREDENTIAL);
        HandshakeProtocol.ClientHello second = protocol.createClientHello(
                secondIdentity, NOW, secondChallenge, CREDENTIAL);

        HandshakeProtocol.ClientHello acceptedFirst = protocol.decodeAndVerifyAgent(
                protocol.encode(first), CREDENTIAL, firstChallenge, NOW, 1_000L);
        HandshakeProtocol.ClientHello acceptedSecond = protocol.decodeAndVerifyAgent(
                protocol.encode(second), CREDENTIAL, secondChallenge, NOW, 1_000L);
        assertEquals(firstIdentity, acceptedFirst.getAgentIdentity());
        assertEquals(secondIdentity, acceptedSecond.getAgentIdentity());

        UUID sessionId = UUID.randomUUID();
        byte[] authorityNonce = new byte[32];
        Arrays.fill(authorityNonce, (byte) 8);
        byte[] firstKey = SessionKeyDerivation.derive(CREDENTIAL, sessionId, firstIdentity,
                "velocity", first.getNonce(), authorityNonce);
        byte[] secondKey = SessionKeyDerivation.derive(CREDENTIAL, sessionId, secondIdentity,
                "velocity", first.getNonce(), authorityNonce);
        assertFalse(Arrays.equals(firstKey, secondKey));

        ProtocolSession firstSender = new ProtocolSession(sessionId, firstIdentity.toString(),
                "velocity", NOW - 1L, NOW + 10_000L, firstKey);
        ProtocolSession secondReceiver = new ProtocolSession(sessionId, "velocity",
                secondIdentity.toString(), NOW - 1L, NOW + 10_000L, secondKey);
        SignedPacket signedByFirst = firstSender.seal(MessageKind.HEARTBEAT, new byte[0], NOW);
        assertReason(AuthenticationException.Reason.BAD_SIGNATURE,
                () -> secondReceiver.open(signedByFirst, NOW));
    }

    @Test
    void sharedKeyProductionPathRejectsLegacyUserDefinedNodeNames() throws Exception {
        HandshakeProtocol.ServerChallenge challenge = protocol.createServerChallenge("velocity", NOW);
        HandshakeProtocol.ClientHello legacy = protocol.createClientHello(
                "survival", NOW, challenge, CREDENTIAL);

        assertReason(AuthenticationException.Reason.SOURCE_MISMATCH,
                () -> protocol.decodeAndVerifyAgent(protocol.encode(legacy), CREDENTIAL,
                        challenge, NOW, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> AgentIdentity.parse("survival"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentIdentity.parse(" " + AgentIdentity.generate() + " "));
        assertThrows(IllegalArgumentException.class,
                () -> AgentIdentity.parse(AgentIdentity.generate().toString().toUpperCase(java.util.Locale.ROOT)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentIdentity.parse("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void replayGuardIsAtomicForConcurrentDuplicateRequests() throws Exception {
        HandshakeReplayGuard guard = new HandshakeReplayGuard();
        byte[] nonce = new byte[32];
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < 32; i++) {
            executor.submit(() -> {
                start.await();
                if (guard.accept("paper-1", nonce, NOW, 1_000L)) {
                    accepted.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, accepted.get());
    }

    private void assertReason(AuthenticationException.Reason expected, ThrowingOperation operation) {
        AuthenticationException exception = assertThrows(AuthenticationException.class, operation::run);
        assertEquals(expected, exception.getReason());
    }

    private static byte[] key(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
