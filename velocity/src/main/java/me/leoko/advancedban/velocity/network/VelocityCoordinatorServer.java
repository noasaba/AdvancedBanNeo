package me.leoko.advancedban.velocity.network;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.CommandManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.network.protocol.AuthenticationException;
import me.leoko.advancedban.network.protocol.AuthorityRequest;
import me.leoko.advancedban.network.protocol.AuthorityRequestCodec;
import me.leoko.advancedban.network.protocol.HandshakeProtocol;
import me.leoko.advancedban.network.protocol.HandshakeReplayGuard;
import me.leoko.advancedban.network.protocol.MessageKind;
import me.leoko.advancedban.network.protocol.ProtocolCodec;
import me.leoko.advancedban.network.protocol.ProtocolConstants;
import me.leoko.advancedban.network.protocol.ProtocolException;
import me.leoko.advancedban.network.protocol.ProtocolPacket;
import me.leoko.advancedban.network.protocol.ProtocolSession;
import me.leoko.advancedban.network.protocol.SessionKeyDerivation;
import me.leoko.advancedban.network.state.RuntimePunishment;
import me.leoko.advancedban.network.state.RuntimePunishmentCodec;
import me.leoko.advancedban.network.state.RuntimePunishmentMapper;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.SQLQuery;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Player-independent authenticated TCP coordinator hosted by Velocity. */
public final class VelocityCoordinatorServer implements AutoCloseable {
    private static final long SESSION_MILLIS = 12L * 60L * 60L * 1000L;
    private static final int SOCKET_TIMEOUT_MILLIS = 45_000;

    private final ProxyServer proxy;
    private final VelocityNetworkSettings settings;
    private final HandshakeProtocol handshake = new HandshakeProtocol();
    private final HandshakeReplayGuard helloReplay = new HandshakeReplayGuard();
    private final ProtocolCodec protocolCodec = new ProtocolCodec();
    private final RuntimePunishmentCodec punishmentCodec = new RuntimePunishmentCodec();
    private final AuthorityRequestCodec requestCodec = new AuthorityRequestCodec();
    private final ConcurrentMap<String, ClientConnection> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AuthorityRequestCodec.Result> completedRequests =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> requestLocks = new ConcurrentHashMap<>();
    private final Queue<String> completedRequestOrder = new ConcurrentLinkedQueue<>();
    private final Semaphore handshakeSlots = new Semaphore(32);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final Set<Thread> connectionThreads = ConcurrentHashMap.newKeySet();
    private final DaemonFactory threadFactory = new DaemonFactory();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<Long, RuntimePunishment> current = Collections.emptyMap();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public VelocityCoordinatorServer(ProxyServer proxy, VelocityNetworkSettings settings) {
        this.proxy = proxy;
        this.settings = settings;
    }

    public void start() throws IOException {
        if (!settings.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        ServerSocket socket = null;
        try {
            refreshSnapshot();
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(settings.getHost(), settings.getPort()));
            serverSocket = socket;
            acceptThread = threadFactory.newThread(this::acceptLoop);
            acceptThread.start();
            Universal.get().log("Authority transport listening on " + settings.getHost() + ':' + settings.getPort());
        } catch (IOException | RuntimeException exception) {
            running.set(false);
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            serverSocket = null;
            throw exception;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (!handshakeSlots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                socket.setSoTimeout(5_000);
                socket.setTcpNoDelay(true);
                openSockets.add(socket);
                Thread connectionThread = threadFactory.newThread(() -> {
                    Thread currentThread = Thread.currentThread();
                    connectionThreads.add(currentThread);
                    try {
                        handle(socket);
                    } finally {
                        connectionThreads.remove(currentThread);
                    }
                });
                connectionThread.start();
            } catch (IOException exception) {
                if (running.get()) {
                    Universal.get().log("Authority transport accept failed.");
                    Universal.get().debugException(exception);
                }
            }
        }
    }

    private void handle(Socket socket) {
        ClientConnection connection = null;
        boolean handshakeSlotHeld = true;
        try (Socket closeable = socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            byte[] encodedHello = readFrame(input, 4096);
            long now = System.currentTimeMillis();
            HandshakeProtocol.ClientHello hello = authenticateHello(encodedHello, now);
            byte[] credential = settings.getCredentials().get(hello.getNodeId());
            if (!helloReplay.accept(hello.getNodeId(), hello.getNonce(), now,
                    ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS * 2L)) {
                throw new AuthenticationException(AuthenticationException.Reason.STALE_SEQUENCE);
            }

            HandshakeProtocol.ServerHello response = handshake.createServerHello(
                    hello, settings.getAuthorityId(), now, now + SESSION_MILLIS, credential);
            writeFrame(output, handshake.encode(response, hello.getNonce()));
            byte[] sessionKey = SessionKeyDerivation.derive(credential, response.getSessionId(),
                    hello.getNodeId(), settings.getAuthorityId(), hello.getNonce(), response.getAuthorityNonce());
            ProtocolSession session = new ProtocolSession(response.getSessionId(), settings.getAuthorityId(),
                    hello.getNodeId(), response.getIssuedAtMillis(), response.getExpiresAtMillis(), sessionKey);
            connection = new ClientConnection(hello.getNodeId(), session, output, socket);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            handshakeSlots.release();
            handshakeSlotHeld = false;
            synchronized (this) {
                ClientConnection previous = clients.put(hello.getNodeId(), connection);
                if (previous != null) {
                    previous.close();
                }
                connection.send(MessageKind.SNAPSHOT,
                        punishmentCodec.encodeSnapshot(new ArrayList<>(current.values())));
            }

            boolean heartbeatOutstanding = false;
            while (running.get() && session.isActive(System.currentTimeMillis())) {
                try {
                    byte[] frame = readFrame(input, ProtocolConstants.MAX_PACKET_BYTES);
                    ProtocolPacket packet = session.open(protocolCodec.decode(frame), System.currentTimeMillis());
                    heartbeatOutstanding = false;
                    handlePacket(connection, packet);
                } catch (SocketTimeoutException timeout) {
                    if (heartbeatOutstanding) {
                        throw new SocketTimeoutException("Agent heartbeat acknowledgement timed out");
                    }
                    connection.send(MessageKind.HEARTBEAT, new byte[0]);
                    heartbeatOutstanding = true;
                }
            }
        } catch (EOFException ignored) {
            // Normal disconnect.
        } catch (AuthenticationException | ProtocolException exception) {
            Universal.get().log("Rejected an invalid Authority/Agent protocol message.");
            Universal.get().debugException(exception);
        } catch (IOException | RuntimeException exception) {
            if (running.get()) {
                Universal.get().log("An Agent transport connection failed.");
                Universal.get().debugException(exception);
            }
        } finally {
            if (handshakeSlotHeld) {
                handshakeSlots.release();
            }
            openSockets.remove(socket);
            if (connection != null) {
                clients.remove(connection.nodeId, connection);
                connection.close();
            }
        }
    }

    private HandshakeProtocol.ClientHello authenticateHello(byte[] encoded, long nowMillis)
            throws ProtocolException, AuthenticationException {
        AuthenticationException failure = null;
        for (Map.Entry<String, byte[]> entry : settings.getCredentials().entrySet()) {
            try {
                HandshakeProtocol.ClientHello hello = handshake.decodeAndVerifyClient(encoded, entry.getValue(),
                        nowMillis, ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS);
                if (!entry.getKey().equals(hello.getNodeId())) {
                    throw new AuthenticationException(AuthenticationException.Reason.SOURCE_MISMATCH);
                }
                return hello;
            } catch (AuthenticationException exception) {
                failure = exception;
            }
        }
        throw failure == null
                ? new AuthenticationException(AuthenticationException.Reason.BAD_SIGNATURE) : failure;
    }

    private void handlePacket(ClientConnection connection, ProtocolPacket packet)
            throws IOException, ProtocolException {
        if (packet.getKind() == MessageKind.HEARTBEAT) {
            connection.send(MessageKind.ACK, new byte[0]);
            return;
        }
        if (packet.getKind() != MessageKind.MUTATION_REQUEST) {
            return;
        }
        AuthorityRequest request = requestCodec.decode(packet.getPayload());
        String idempotencyKey = connection.nodeId + ':' + request.getRequestId();
        AuthorityRequestCodec.Result result = executeOnce(idempotencyKey, request);
        connection.send(MessageKind.MUTATION_RESULT, requestCodec.encodeResult(
                result.getRequestId(), result.isSuccess(), result.getDetail()));
    }

    private AuthorityRequestCodec.Result executeOnce(String idempotencyKey, AuthorityRequest request)
            throws ProtocolException {
        AuthorityRequestCodec.Result completed = completedRequests.get(idempotencyKey);
        if (completed != null) {
            return completed;
        }
        Object lock = requestLocks.computeIfAbsent(idempotencyKey, ignored -> new Object());
        try {
            synchronized (lock) {
                completed = completedRequests.get(idempotencyKey);
                if (completed != null) {
                    return completed;
                }
                boolean success = execute(request);
                completed = requestCodec.decodeResult(requestCodec.encodeResult(
                        request.getRequestId(), success, success ? "accepted" : "rejected"));
                completedRequests.put(idempotencyKey, completed);
                completedRequestOrder.add(idempotencyKey);
                while (completedRequests.size() > 10_000) {
                    String oldest = completedRequestOrder.poll();
                    if (oldest == null) {
                        break;
                    }
                    completedRequests.remove(oldest);
                }
                return completed;
            }
        } finally {
            requestLocks.remove(idempotencyKey, lock);
        }
    }

    private boolean execute(AuthorityRequest request) {
        try {
            List<String> values = request.getValues();
            switch (request.getAction()) {
                case COMMAND:
                    if (values.isEmpty()) {
                        return false;
                    }
                    Optional<CommandSource> sender = resolveSender(request);
                    if (!sender.isPresent()) {
                        return false;
                    }
                    CommandManager.get().onCommand(sender.get(), values.get(0),
                            values.subList(1, values.size()).toArray(new String[0]));
                    return true;
                case CREATE:
                    if (values.size() != 8) {
                        return false;
                    }
                    return Punishment.createChecked(values.get(0), values.get(1), emptyToNull(values.get(2)),
                            values.get(3), PunishmentType.valueOf(values.get(4)), Long.parseLong(values.get(5)),
                            emptyToNull(values.get(6)), Boolean.parseBoolean(values.get(7)));
                case DELETE:
                    if (values.size() != 4) {
                        return false;
                    }
                    Punishment deleting = PunishmentManager.get().getPunishment(Integer.parseInt(values.get(0)));
                    return deleting != null && deleting.deleteChecked(emptyToNull(values.get(1)),
                            Boolean.parseBoolean(values.get(2)), Boolean.parseBoolean(values.get(3)));
                case UPDATE_REASON:
                    if (values.size() != 2) {
                        return false;
                    }
                    Punishment updating = PunishmentManager.get().getPunishment(Integer.parseInt(values.get(0)));
                    return updating != null && updating.updateReasonChecked(values.get(1));
                case DELETE_ALL:
                    if (values.size() < 3) {
                        return false;
                    }
                    Collection<Punishment> deletingAll = new ArrayList<>();
                    for (String value : values.subList(2, values.size())) {
                        Punishment punishment = PunishmentManager.get().getPunishment(Integer.parseInt(value));
                        if (punishment == null) {
                            return false;
                        }
                        deletingAll.add(punishment);
                    }
                    return Punishment.deleteAllChecked(new ArrayList<>(deletingAll),
                            request.getSenderName(), Boolean.parseBoolean(values.get(0)),
                            Boolean.parseBoolean(values.get(1)));
                default:
                    return false;
            }
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Optional<CommandSource> resolveSender(AuthorityRequest request) {
        if (request.getSenderKind() == AuthorityRequest.SenderKind.CONSOLE) {
            return Optional.of(proxy.getConsoleCommandSource());
        }
        if (request.getSenderKind() != AuthorityRequest.SenderKind.PLAYER) {
            return Optional.empty();
        }
        try {
            UUID uuid = UUID.fromString(dashed(request.getSenderUuid()));
            Optional<Player> player = proxy.getPlayer(uuid);
            if (player.isPresent() && player.get().getUsername().equalsIgnoreCase(request.getSenderName())) {
                return Optional.of(player.get());
            }
        } catch (IllegalArgumentException ignored) {
        }
        return Optional.empty();
    }

    public synchronized void refreshAndBroadcast() {
        if (!running.get()) {
            return;
        }
        Map<Long, RuntimePunishment> previous = current;
        Map<Long, RuntimePunishment> next = refreshSnapshot();
        for (Map.Entry<Long, RuntimePunishment> entry : next.entrySet()) {
            RuntimePunishment old = previous.get(entry.getKey());
            if (old == null) {
                broadcast(MessageKind.APPLY, punishmentCodec.encode(entry.getValue()));
            } else if (!old.equals(entry.getValue())) {
                broadcast(MessageKind.UPDATE, punishmentCodec.encode(entry.getValue()));
            }
        }
        for (Long id : previous.keySet()) {
            if (!next.containsKey(id)) {
                broadcast(MessageKind.REVOKE, punishmentCodec.encodeRevoke(id));
            }
        }
    }

    private Map<Long, RuntimePunishment> refreshSnapshot() {
        Map<Long, RuntimePunishment> snapshot = new LinkedHashMap<>();
        for (Punishment punishment : PunishmentManager.get().getPunishments(SQLQuery.SELECT_ALL_PUNISHMENTS)) {
            if (!punishment.isExpired()) {
                RuntimePunishment runtime = RuntimePunishmentMapper.fromLegacy(punishment);
                snapshot.put(runtime.getId(), runtime);
            }
        }
        current = Collections.unmodifiableMap(snapshot);
        return current;
    }

    private void broadcast(MessageKind kind, byte[] payload) {
        for (ClientConnection connection : clients.values()) {
            try {
                connection.send(kind, payload);
            } catch (IOException exception) {
                clients.remove(connection.nodeId, connection);
                connection.close();
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        for (ClientConnection connection : clients.values()) {
            connection.close();
        }
        clients.clear();
        for (Socket socket : openSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        openSockets.clear();
        Thread accepting = acceptThread;
        if (accepting != null) {
            accepting.interrupt();
        }
        for (Thread thread : connectionThreads) {
            thread.interrupt();
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

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String dashed(String uuid) {
        String value = uuid.replace("-", "");
        if (value.length() != 32) {
            return uuid;
        }
        return value.substring(0, 8) + '-' + value.substring(8, 12) + '-' + value.substring(12, 16)
                + '-' + value.substring(16, 20) + '-' + value.substring(20);
    }

    private final class ClientConnection implements AutoCloseable {
        private final String nodeId;
        private final ProtocolSession session;
        private final DataOutputStream output;
        private final Socket socket;
        private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final Thread writer;

        private ClientConnection(String nodeId, ProtocolSession session, DataOutputStream output, Socket socket) {
            this.nodeId = nodeId;
            this.session = session;
            this.output = output;
            this.socket = socket;
            this.writer = threadFactory.newThread(this::writeLoop);
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
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class DaemonFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "advancedban-authority-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }
}
