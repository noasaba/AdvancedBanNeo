package me.leoko.advancedban.bukkit.network;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.network.protocol.AuthenticationException;
import me.leoko.advancedban.network.protocol.AuthorityRequest;
import me.leoko.advancedban.network.protocol.AuthorityRequestCodec;
import me.leoko.advancedban.network.protocol.HandshakeProtocol;
import me.leoko.advancedban.network.protocol.MessageKind;
import me.leoko.advancedban.network.protocol.ProtocolCodec;
import me.leoko.advancedban.network.protocol.ProtocolConstants;
import me.leoko.advancedban.network.protocol.ProtocolException;
import me.leoko.advancedban.network.protocol.ProtocolPacket;
import me.leoko.advancedban.network.protocol.ProtocolSession;
import me.leoko.advancedban.network.protocol.SessionKeyDerivation;
import me.leoko.advancedban.network.state.AgentPunishmentState;
import me.leoko.advancedban.network.state.RuntimePunishment;
import me.leoko.advancedban.network.state.RuntimePunishmentCodec;
import me.leoko.advancedban.network.state.RuntimePunishmentMapper;
import me.leoko.advancedban.runtime.RuntimeRole;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent DB-less Paper Agent connection. */
public final class PaperAgentClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 45_000;

    private final PaperNetworkSettings settings;
    private final HandshakeProtocol handshake = new HandshakeProtocol();
    private final ProtocolCodec protocolCodec = new ProtocolCodec();
    private final RuntimePunishmentCodec punishmentCodec = new RuntimePunishmentCodec();
    private final AuthorityRequestCodec requestCodec = new AuthorityRequestCodec();
    private final AgentPunishmentState state = new AgentPunishmentState();
    private final Map<UUID, CompletableFuture<AuthorityRequestCodec.Result>> pending =
            new ConcurrentHashMap<>();
    private final Map<UUID, Object> submitLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Connection connection;
    private Thread worker;

    public PaperAgentClient(PaperNetworkSettings settings) {
        this.settings = settings;
    }

    public void start() {
        if (!settings.isValidAgent() || !running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::reconnectLoop, "advancedban-agent");
        worker.setDaemon(true);
        worker.start();
    }

    private void reconnectLoop() {
        long delay = 1_000L;
        while (running.get()) {
            try {
                connectAndRead();
                delay = 1_000L;
            } catch (AuthenticationException | ProtocolException exception) {
                Universal.get().log("Authority authentication failed; remaining a degraded Agent.");
                Universal.get().debugException(exception);
            } catch (IOException | RuntimeException exception) {
                Universal.get().debug("Authority unavailable; remaining a degraded Agent.");
            } finally {
                markDegraded();
                Connection active = connection;
                connection = null;
                if (active != null) {
                    active.close();
                }
                pending.values().forEach(future -> future.completeExceptionally(
                        new IOException("Authority connection closed")));
                pending.clear();
            }
            if (running.get()) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = Math.min(delay * 2L, 30_000L);
            }
        }
    }

    private void connectAndRead() throws IOException, ProtocolException, AuthenticationException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(settings.getHost(), settings.getPort()), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                long now = System.currentTimeMillis();
                byte[] credential = settings.getCredential();
                HandshakeProtocol.ClientHello hello = handshake.createClientHello(settings.getNodeId(), now, credential);
                writeFrame(output, handshake.encode(hello));
                HandshakeProtocol.ServerHello response = handshake.decodeAndVerifyServer(
                        readFrame(input, 4096), hello.getNonce(), credential, System.currentTimeMillis(),
                        ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS);
                byte[] sessionKey = SessionKeyDerivation.derive(credential, response.getSessionId(),
                        settings.getNodeId(), response.getAuthorityNode(), hello.getNonce(), response.getAuthorityNonce());
                ProtocolSession session = new ProtocolSession(response.getSessionId(), settings.getNodeId(),
                        response.getAuthorityNode(), response.getIssuedAtMillis(), response.getExpiresAtMillis(), sessionKey);
                Connection established = new Connection(socket, input, output, session);
                connection = established;
                boolean heartbeatOutstanding = false;

                while (running.get() && session.isActive(System.currentTimeMillis())) {
                    try {
                        ProtocolPacket packet = session.open(protocolCodec.decode(
                                readFrame(input, ProtocolConstants.MAX_PACKET_BYTES)), System.currentTimeMillis());
                        heartbeatOutstanding = false;
                        handle(established, packet);
                    } catch (SocketTimeoutException timeout) {
                        if (heartbeatOutstanding) {
                            throw new SocketTimeoutException("Authority heartbeat acknowledgement timed out");
                        }
                        established.send(MessageKind.HEARTBEAT, new byte[0]);
                        heartbeatOutstanding = true;
                    }
                }
            }
        }
    }

    private void handle(Connection established, ProtocolPacket packet)
            throws ProtocolException, IOException {
        switch (packet.getKind()) {
            case SNAPSHOT:
                List<RuntimePunishment> snapshot = punishmentCodec.decodeSnapshot(packet.getPayload());
                state.replaceSnapshot(snapshot);
                List<me.leoko.advancedban.utils.Punishment> legacy = new ArrayList<>(snapshot.size());
                for (RuntimePunishment punishment : snapshot) {
                    legacy.add(RuntimePunishmentMapper.toLegacy(punishment));
                }
                PunishmentManager.get().replaceAgentSnapshot(legacy);
                Universal.get().updateAgentRole(RuntimeRole.AGENT);
                established.send(MessageKind.ACK, new byte[0]);
                break;
            case APPLY:
            case UPDATE:
                RuntimePunishment applied = punishmentCodec.decode(packet.getPayload());
                if (packet.getKind() == MessageKind.APPLY) {
                    state.apply(applied);
                } else {
                    state.update(applied);
                }
                PunishmentManager.get().applyAgentPunishment(RuntimePunishmentMapper.toLegacy(applied));
                established.send(MessageKind.ACK, new byte[0]);
                break;
            case REVOKE:
                long id = punishmentCodec.decodeRevoke(packet.getPayload());
                state.revoke(id);
                PunishmentManager.get().revokeAgentPunishment((int) id);
                established.send(MessageKind.ACK, new byte[0]);
                break;
            case HEARTBEAT:
                established.send(MessageKind.ACK, new byte[0]);
                break;
            case MUTATION_RESULT:
                AuthorityRequestCodec.Result result = requestCodec.decodeResult(packet.getPayload());
                CompletableFuture<AuthorityRequestCodec.Result> future = pending.remove(result.getRequestId());
                if (future != null) {
                    future.complete(result);
                }
                break;
            case ACK:
            default:
                break;
        }
    }

    public boolean submit(AuthorityRequest request) {
        Object requestLock = submitLocks.computeIfAbsent(request.getRequestId(), ignored -> new Object());
        try {
            synchronized (requestLock) {
                return submitLocked(request);
            }
        } finally {
            submitLocks.remove(request.getRequestId(), requestLock);
        }
    }

    private boolean submitLocked(AuthorityRequest request) {
        if (!running.get() || Universal.get().getRuntimeRole() != RuntimeRole.AGENT) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        byte[] encoded = requestCodec.encode(request);
        while (running.get() && System.nanoTime() < deadline) {
            Connection active = connection;
            if (active == null || Universal.get().getRuntimeRole() != RuntimeRole.AGENT) {
                if (!pauseForRetry(deadline)) {
                    break;
                }
                continue;
            }
            CompletableFuture<AuthorityRequestCodec.Result> result = new CompletableFuture<>();
            pending.put(request.getRequestId(), result);
            try {
                active.send(MessageKind.MUTATION_REQUEST, encoded);
                long remainingMillis = Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                return result.get(Math.min(1_500L, remainingMillis), TimeUnit.MILLISECONDS).isSuccess();
            } catch (java.util.concurrent.TimeoutException timeout) {
                // Retry the exact same authenticated request ID. The Authority
                // returns its cached result instead of repeating the mutation.
            } catch (Exception exception) {
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
            } finally {
                pending.remove(request.getRequestId(), result);
            }
            if (!pauseForRetry(deadline)) {
                break;
            }
        }
        return false;
    }

    private static boolean pauseForRetry(long deadline) {
        if (System.nanoTime() >= deadline) {
            return false;
        }
        try {
            Thread.sleep(50L);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public AgentPunishmentState getState() {
        return state;
    }

    private void markDegraded() {
        if (Universal.get().getRuntimeRole().isAgent()) {
            state.markUnavailable();
            PunishmentManager.get().markAgentSnapshotUnavailable();
            Universal.get().updateAgentRole(RuntimeRole.AGENT_DEGRADED);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Connection active = connection;
        if (active != null) {
            active.close();
        }
        Thread activeWorker = worker;
        if (activeWorker != null) {
            activeWorker.interrupt();
        }
    }

    private static byte[] readFrame(DataInputStream input, int maximum) throws IOException, ProtocolException {
        int length = input.readInt();
        if (length <= 0 || length > maximum) {
            throw new ProtocolException("invalid transport frame length");
        }
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    private final class Connection implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final ProtocolSession session;
        private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final Thread writer;

        private Connection(Socket socket, DataInputStream input, DataOutputStream output,
                           ProtocolSession session) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.session = session;
            this.writer = new Thread(this::writeLoop, "advancedban-agent-writer");
            this.writer.setDaemon(true);
            this.writer.start();
        }

        private synchronized void send(MessageKind kind, byte[] payload) throws IOException {
            if (!open.get() || !outbound.offer(
                    protocolCodec.encode(session.seal(kind, payload, System.currentTimeMillis())))) {
                close();
                throw new IOException("Agent outbound queue is unavailable");
            }
        }

        private void writeLoop() {
            try {
                while (open.get()) {
                    writeFrame(output, outbound.take());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                close();
            }
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            session.close();
            writer.interrupt();
            try {
                input.close();
            } catch (IOException ignored) {
            }
            try {
                output.close();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
