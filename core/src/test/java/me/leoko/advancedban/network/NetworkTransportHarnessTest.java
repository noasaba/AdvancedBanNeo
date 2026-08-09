package me.leoko.advancedban.network;

import me.leoko.advancedban.network.protocol.AuthorityRequest;
import me.leoko.advancedban.network.protocol.AuthorityRequestCodec;
import me.leoko.advancedban.network.protocol.HandshakeProtocol;
import me.leoko.advancedban.network.protocol.MessageKind;
import me.leoko.advancedban.network.protocol.ProtocolCodec;
import me.leoko.advancedban.network.protocol.ProtocolConstants;
import me.leoko.advancedban.network.protocol.ProtocolPacket;
import me.leoko.advancedban.network.protocol.ProtocolSession;
import me.leoko.advancedban.network.protocol.SessionKeyDerivation;
import me.leoko.advancedban.network.state.AgentPunishmentState;
import me.leoko.advancedban.network.state.RuntimePunishment;
import me.leoko.advancedban.network.state.RuntimePunishmentCodec;
import me.leoko.advancedban.network.state.RuntimePunishmentType;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end loopback harness for the player-independent Authority/Agent transport contract. */
class NetworkTransportHarnessTest {
    private static final String AGENT = "paper-1";
    private static final String AUTHORITY = "velocity";
    private static final byte[] CREDENTIAL = credential();
    private static final long TIMEOUT_MILLIS = 5_000L;

    @Test
    void authenticatedSocketHandshakeSynchronizesStateAndDeduplicatesMutationRequests() throws Exception {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        listener.setSoTimeout((int) TIMEOUT_MILLIS);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger mutationsExecuted = new AtomicInteger();
        Future<?> authority = executor.submit(() -> runAuthority(listener, mutationsExecuted));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
                    (int) TIMEOUT_MILLIS);
            socket.setSoTimeout((int) TIMEOUT_MILLIS);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            HandshakeProtocol handshake = new HandshakeProtocol();
            long now = System.currentTimeMillis();
            HandshakeProtocol.ClientHello clientHello = handshake.createClientHello(AGENT, now, CREDENTIAL);
            writeFrame(output, handshake.encode(clientHello));
            HandshakeProtocol.ServerHello serverHello = handshake.decodeAndVerifyServer(
                    readFrame(input, 4096), clientHello.getNonce(), CREDENTIAL,
                    System.currentTimeMillis(), ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS);
            byte[] sessionKey = SessionKeyDerivation.derive(CREDENTIAL, serverHello.getSessionId(),
                    AGENT, AUTHORITY, clientHello.getNonce(), serverHello.getAuthorityNonce());
            ProtocolSession agentSession = new ProtocolSession(serverHello.getSessionId(), AGENT, AUTHORITY,
                    serverHello.getIssuedAtMillis(), serverHello.getExpiresAtMillis(), sessionKey);

            ProtocolCodec protocol = new ProtocolCodec();
            RuntimePunishmentCodec punishmentCodec = new RuntimePunishmentCodec();
            AgentPunishmentState state = new AgentPunishmentState();

            ProtocolPacket snapshot = receive(input, protocol, agentSession);
            assertEquals(MessageKind.SNAPSHOT, snapshot.getKind());
            state.replaceSnapshot(punishmentCodec.decodeSnapshot(snapshot.getPayload()));
            assertTrue(state.isSnapshotReady());
            assertTrue(state.isMuted("player-uuid", System.currentTimeMillis()));

            ProtocolPacket apply = receive(input, protocol, agentSession);
            assertEquals(MessageKind.APPLY, apply.getKind());
            assertTrue(state.apply(punishmentCodec.decode(apply.getPayload())));

            ProtocolPacket duplicateApply = receive(input, protocol, agentSession);
            assertEquals(MessageKind.APPLY, duplicateApply.getKind());
            assertFalse(state.apply(punishmentCodec.decode(duplicateApply.getPayload())),
                    "duplicate semantic updates must be idempotent even with a fresh packet sequence");

            ProtocolPacket update = receive(input, protocol, agentSession);
            assertEquals(MessageKind.UPDATE, update.getKind());
            assertTrue(state.update(punishmentCodec.decode(update.getPayload())));
            assertEquals("updated", state.getActive(2L, System.currentTimeMillis()).get().getReason());

            ProtocolPacket revoke = receive(input, protocol, agentSession);
            assertEquals(MessageKind.REVOKE, revoke.getKind());
            assertTrue(state.revoke(punishmentCodec.decodeRevoke(revoke.getPayload())));
            assertFalse(state.getActive(2L, System.currentTimeMillis()).isPresent());

            AuthorityRequestCodec requests = new AuthorityRequestCodec();
            UUID requestId = UUID.randomUUID();
            AuthorityRequest request = new AuthorityRequest(requestId, AuthorityRequest.Action.COMMAND,
                    AuthorityRequest.SenderKind.CONSOLE, "", "console",
                    Arrays.asList("mute", "Player", "reason"));
            send(output, protocol, agentSession, MessageKind.MUTATION_REQUEST, requests.encode(request));
            AuthorityRequestCodec.Result first = requests.decodeResult(
                    receive(input, protocol, agentSession).getPayload());
            send(output, protocol, agentSession, MessageKind.MUTATION_REQUEST, requests.encode(request));
            AuthorityRequestCodec.Result duplicate = requests.decodeResult(
                    receive(input, protocol, agentSession).getPayload());

            assertTrue(first.isSuccess());
            assertTrue(duplicate.isSuccess());
            assertEquals(requestId, first.getRequestId());
            assertEquals(requestId, duplicate.getRequestId());
        } finally {
            listener.close();
            authority.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            executor.shutdownNow();
        }

        assertEquals(1, mutationsExecuted.get(),
                "same idempotency key must execute at the Authority exactly once");
    }

    private static void runAuthority(ServerSocket listener, AtomicInteger mutationsExecuted) {
        try (Socket socket = listener.accept()) {
            socket.setSoTimeout((int) TIMEOUT_MILLIS);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            HandshakeProtocol handshake = new HandshakeProtocol();
            long now = System.currentTimeMillis();
            HandshakeProtocol.ClientHello client = handshake.decodeAndVerifyClient(
                    readFrame(input, 4096), CREDENTIAL, now,
                    ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS);
            HandshakeProtocol.ServerHello server = handshake.createServerHello(
                    client, AUTHORITY, now, now + 60_000L, CREDENTIAL);
            writeFrame(output, handshake.encode(server, client.getNonce()));
            byte[] sessionKey = SessionKeyDerivation.derive(CREDENTIAL, server.getSessionId(),
                    AGENT, AUTHORITY, client.getNonce(), server.getAuthorityNonce());
            ProtocolSession authoritySession = new ProtocolSession(server.getSessionId(), AUTHORITY, AGENT,
                    server.getIssuedAtMillis(), server.getExpiresAtMillis(), sessionKey);
            ProtocolCodec protocol = new ProtocolCodec();
            RuntimePunishmentCodec punishments = new RuntimePunishmentCodec();
            long start = System.currentTimeMillis() - 1_000L;
            RuntimePunishment snapshotMute = new RuntimePunishment(1L, "player-uuid", null,
                    RuntimePunishmentType.MUTE, "snapshot", "console", start, -1L, true, false, "permanent");
            RuntimePunishment applied = new RuntimePunishment(2L, "other-uuid", null,
                    RuntimePunishmentType.TEMP_MUTE, "applied", "console", start,
                    start + 60_000L, false, false, "1m");
            RuntimePunishment updated = new RuntimePunishment(2L, "other-uuid", null,
                    RuntimePunishmentType.TEMP_MUTE, "updated", "console", start,
                    start + 120_000L, false, false, "2m");

            send(output, protocol, authoritySession, MessageKind.SNAPSHOT,
                    punishments.encodeSnapshot(Collections.singletonList(snapshotMute)));
            send(output, protocol, authoritySession, MessageKind.APPLY, punishments.encode(applied));
            send(output, protocol, authoritySession, MessageKind.APPLY, punishments.encode(applied));
            send(output, protocol, authoritySession, MessageKind.UPDATE, punishments.encode(updated));
            send(output, protocol, authoritySession, MessageKind.REVOKE, punishments.encodeRevoke(2L));

            AuthorityRequestCodec requestCodec = new AuthorityRequestCodec();
            Set<UUID> completed = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                ProtocolPacket packet = receive(input, protocol, authoritySession);
                AuthorityRequest request = requestCodec.decode(packet.getPayload());
                if (completed.add(request.getRequestId())) {
                    mutationsExecuted.incrementAndGet();
                }
                send(output, protocol, authoritySession, MessageKind.MUTATION_RESULT,
                        requestCodec.encodeResult(request.getRequestId(), true, "accepted"));
            }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static ProtocolPacket receive(DataInputStream input, ProtocolCodec protocol,
                                          ProtocolSession session) throws Exception {
        return session.open(protocol.decode(readFrame(input, ProtocolConstants.MAX_PACKET_BYTES)),
                System.currentTimeMillis());
    }

    private static void send(DataOutputStream output, ProtocolCodec protocol, ProtocolSession session,
                             MessageKind kind, byte[] payload) throws Exception {
        writeFrame(output, protocol.encode(session.seal(kind, payload, System.currentTimeMillis())));
    }

    private static byte[] readFrame(DataInputStream input, int maximum) throws Exception {
        int length = input.readInt();
        if (length <= 0 || length > maximum) {
            throw new AssertionError("invalid frame length: " + length);
        }
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws Exception {
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    private static byte[] credential() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 42);
        return key;
    }
}
