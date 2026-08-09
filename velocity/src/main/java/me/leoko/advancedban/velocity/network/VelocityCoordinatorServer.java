package me.leoko.advancedban.velocity.network;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.CommandManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.network.protocol.AuthenticationException;
import me.leoko.advancedban.network.protocol.AuthenticatedLiveness;
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
import me.leoko.advancedban.utils.Command;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Player-independent authenticated TCP coordinator hosted by Velocity. */
public final class VelocityCoordinatorServer implements AutoCloseable {
    private static final long SESSION_MILLIS = 12L * 60L * 60L * 1000L;
    private static final int SOCKET_TIMEOUT_MILLIS = 45_000;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

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
    private volatile Map<Long, RuntimePunishment> history = Collections.emptyMap();
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
            long handshakeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            HandshakeProtocol.ServerChallenge challenge = handshake.createServerChallenge(
                    settings.getAuthorityId(), System.currentTimeMillis());
            writeFrame(output, handshake.encode(challenge));
            byte[] encodedHello = readFrameBeforeDeadline(input, socket, 4096, handshakeDeadline);
            long now = System.currentTimeMillis();
            HandshakeProtocol.ClientHello hello = authenticateHello(encodedHello, challenge, now);
            byte[] credential = settings.getCredentials().get(hello.getNodeId());
            if (!helloReplay.accept(hello.getNodeId(), hello.getNonce(), now,
                    ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS * 2L)) {
                throw new AuthenticationException(AuthenticationException.Reason.STALE_SEQUENCE);
            }

            HandshakeProtocol.ServerHello response = handshake.createServerHello(
                    hello, challenge, now, now + SESSION_MILLIS, credential);
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
                sendHistorySnapshot(connection, new ArrayList<>(history.values()));
            }

            AuthenticatedLiveness liveness = new AuthenticatedLiveness(
                    monotonicMillis(), SOCKET_TIMEOUT_MILLIS, SOCKET_TIMEOUT_MILLIS);
            while (running.get() && session.isActive(System.currentTimeMillis())) {
                try {
                    byte[] frame = readFrame(input, ProtocolConstants.MAX_PACKET_BYTES);
                    ProtocolPacket packet = session.open(protocolCodec.decode(frame), System.currentTimeMillis());
                    liveness.authenticatedInbound(monotonicMillis());
                    handlePacket(connection, packet);
                } catch (SocketTimeoutException timeout) {
                    AuthenticatedLiveness.Action action = liveness.onReadTimeout(monotonicMillis());
                    if (action == AuthenticatedLiveness.Action.CLOSE) {
                        throw new SocketTimeoutException("Agent heartbeat acknowledgement timed out");
                    }
                    if (action == AuthenticatedLiveness.Action.SEND_HEARTBEAT) {
                        connection.send(MessageKind.HEARTBEAT, new byte[0]);
                    }
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

    private HandshakeProtocol.ClientHello authenticateHello(byte[] encoded,
                                                            HandshakeProtocol.ServerChallenge challenge,
                                                            long nowMillis)
            throws ProtocolException, AuthenticationException {
        AuthenticationException failure = null;
        for (Map.Entry<String, byte[]> entry : settings.getCredentials().entrySet()) {
            try {
                HandshakeProtocol.ClientHello hello = handshake.decodeAndVerifyClient(encoded, entry.getValue(),
                        challenge, nowMillis, ProtocolConstants.DEFAULT_CLOCK_SKEW_MILLIS);
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
        if (packet.getKind() == MessageKind.READY) {
            // The Agent emits READY only after atomically installing
            // both the active and complete public history snapshots.
            connection.snapshotAcknowledged.compareAndSet(false, true);
            return;
        }
        if (packet.getKind() == MessageKind.ACK) {
            // An ACK is only a heartbeat response and must never promote an
            // incompletely synchronized backend to routing eligibility.
            return;
        }
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
                CommandExecution commandExecution;
                if (request.getAction() == AuthorityRequest.Action.COMMAND) {
                    commandExecution = executeCommand(request);
                } else if (request.getAction() == AuthorityRequest.Action.TAB_COMPLETE) {
                    commandExecution = executeTabCompletion(request);
                } else {
                    commandExecution = null;
                }
                boolean success = commandExecution == null ? execute(request) : commandExecution.accepted;
                String detail = commandExecution == null
                        ? (success ? "accepted" : "rejected") : commandExecution.detail;
                completed = requestCodec.decodeResult(requestCodec.encodeResult(
                        request.getRequestId(), success, detail));
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
                    return false;
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

    private CommandExecution executeCommand(AuthorityRequest request) {
        List<String> values = request.getValues();
        if (values.isEmpty()) {
            return new CommandExecution(false, "rejected");
        }
        Optional<CommandSource> resolved = resolveSender(request);
        if (!resolved.isPresent()) {
            return new CommandExecution(false, "rejected");
        }
        if (request.getSenderKind() != AuthorityRequest.SenderKind.CONSOLE) {
            boolean accepted = CommandManager.get().executeNow(resolved.get(), values.get(0),
                    values.subList(1, values.size()).toArray(new String[0]));
            return new CommandExecution(accepted, accepted ? "accepted" : "rejected");
        }

        CommandSource authorityConsole = resolved.get();
        List<String> output = new ArrayList<>();
        CommandSource capture = (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(), new Class<?>[]{CommandSource.class},
                (proxyInstance, method, arguments) -> {
                    if ("sendMessage".equals(method.getName()) && arguments != null
                            && arguments.length > 0 && arguments[0] instanceof Component) {
                        output.add(LEGACY.serialize((Component) arguments[0]));
                        return null;
                    }
                    try {
                        return method.invoke(authorityConsole, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        boolean accepted = CommandManager.get().executeNow(capture, values.get(0),
                values.subList(1, values.size()).toArray(new String[0]));
        String detail = output.isEmpty() ? (accepted ? "accepted" : "rejected") : String.join("\n", output);
        // The wire codec limits UTF-8 bytes. Four thousand UTF-16 code units
        // stay below that bound even for supplementary characters.
        if (detail.length() > 4_000) {
            detail = detail.substring(0, 4_000);
        }
        return new CommandExecution(accepted, detail);
    }

    private CommandExecution executeTabCompletion(AuthorityRequest request) {
        List<String> values = request.getValues();
        if (values.isEmpty()) {
            return new CommandExecution(false, "");
        }
        Optional<CommandSource> sender = resolveSender(request);
        Command command = Command.getByName(values.get(0));
        if (!sender.isPresent() || command == null || command.getTabCompleter() == null) {
            return new CommandExecution(false, "");
        }
        if (command.getPermission() != null && !Universal.get().hasPerms(sender.get(), command.getPermission())) {
            return new CommandExecution(true, "");
        }
        List<String> suggestions = command.getTabCompleter().onTabComplete(sender.get(),
                values.subList(1, values.size()).toArray(new String[0]));
        StringBuilder encoded = new StringBuilder();
        for (String suggestion : suggestions) {
            if (suggestion == null || suggestion.indexOf('\u001f') >= 0) {
                continue;
            }
            if (encoded.length() + suggestion.length() + 1 > 4_000) {
                break;
            }
            if (encoded.length() > 0) {
                encoded.append('\u001f');
            }
            encoded.append(suggestion);
        }
        return new CommandExecution(true, encoded.toString());
    }

    private static final class CommandExecution {
        private final boolean accepted;
        private final String detail;

        private CommandExecution(boolean accepted, String detail) {
            this.accepted = accepted;
            this.detail = detail;
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
        Map<Long, RuntimePunishment> previousHistory = history;
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
        broadcastHistoryChanges(previousHistory, history);
    }

    /** True only while the configured backend has an authenticated session and ACKed its full active snapshot. */
    public boolean isAgentReady(String backendServerName) {
        if (!settings.isEnabled() || backendServerName == null
                || !settings.isAllowedNode(backendServerName)) {
            return false;
        }
        ClientConnection connection = clients.get(backendServerName);
        return connection != null && connection.isReady();
    }

    public boolean isAgentRoutingEnforced() {
        return settings.isEnabled();
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
        Map<Long, RuntimePunishment> historySnapshot = new LinkedHashMap<>();
        for (Punishment punishment : PunishmentManager.get().getPunishments(
                SQLQuery.SELECT_ALL_PUNISHMENTS_HISTORY)) {
            RuntimePunishment runtime = RuntimePunishmentMapper.fromLegacy(punishment);
            historySnapshot.put(runtime.getId(), runtime);
        }
        history = Collections.unmodifiableMap(historySnapshot);
        return current;
    }

    private void broadcastHistoryChanges(Map<Long, RuntimePunishment> previous,
                                         Map<Long, RuntimePunishment> next) {
        boolean appendOnly = next.size() >= previous.size();
        if (appendOnly) {
            for (Map.Entry<Long, RuntimePunishment> entry : previous.entrySet()) {
                if (!entry.getValue().equals(next.get(entry.getKey()))) {
                    appendOnly = false;
                    break;
                }
            }
        }
        if (!appendOnly) {
            for (ClientConnection connection : clients.values()) {
                try {
                    sendHistorySnapshot(connection, new ArrayList<>(next.values()));
                } catch (IOException | RuntimeException exception) {
                    clients.remove(connection.nodeId, connection);
                    connection.close();
                }
            }
            return;
        }
        for (Map.Entry<Long, RuntimePunishment> entry : next.entrySet()) {
            if (!previous.containsKey(entry.getKey())) {
                broadcast(MessageKind.HISTORY_APPEND, punishmentCodec.encode(entry.getValue()));
            }
        }
    }

    private void sendHistorySnapshot(ClientConnection connection, List<RuntimePunishment> snapshot)
            throws IOException {
        connection.send(MessageKind.HISTORY_SNAPSHOT_BEGIN, new byte[0]);
        sendHistoryRange(connection, snapshot, 0, snapshot.size());
        connection.send(MessageKind.HISTORY_SNAPSHOT_END, new byte[0]);
    }

    private void sendHistoryRange(ClientConnection connection, List<RuntimePunishment> snapshot,
                                  int from, int to) throws IOException {
        if (from >= to) {
            return;
        }
        try {
            connection.send(MessageKind.HISTORY_SNAPSHOT_CHUNK,
                    punishmentCodec.encodeSnapshot(snapshot.subList(from, to)));
        } catch (IllegalArgumentException tooLarge) {
            if (to - from == 1) {
                throw tooLarge;
            }
            int middle = from + (to - from) / 2;
            sendHistoryRange(connection, snapshot, from, middle);
            sendHistoryRange(connection, snapshot, middle, to);
        }
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
        long joinDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        joinUntil(accepting, joinDeadline);
        for (Thread thread : new ArrayList<>(connectionThreads)) {
            joinUntil(thread, joinDeadline);
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

    private static byte[] readFrameBeforeDeadline(DataInputStream input, Socket socket, int maximum,
                                                   long deadlineNanos)
            throws IOException, ProtocolException {
        byte[] lengthBytes = new byte[4];
        readFullyBeforeDeadline(input, socket, lengthBytes, deadlineNanos);
        int length = ((lengthBytes[0] & 0xff) << 24) | ((lengthBytes[1] & 0xff) << 16)
                | ((lengthBytes[2] & 0xff) << 8) | (lengthBytes[3] & 0xff);
        if (length <= 0 || length > maximum) {
            throw new ProtocolException("invalid transport frame length");
        }
        byte[] frame = new byte[length];
        readFullyBeforeDeadline(input, socket, frame, deadlineNanos);
        return frame;
    }

    private static void readFullyBeforeDeadline(DataInputStream input, Socket socket, byte[] target,
                                                long deadlineNanos) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new SocketTimeoutException("Agent handshake timed out");
            }
            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new EOFException("Agent closed during handshake");
            }
            offset += read;
        }
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static long monotonicMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private static void joinUntil(Thread thread, long deadlineNanos) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return;
        }
        try {
            long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
            thread.join(millis, nanos);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
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
        private final AtomicBoolean snapshotAcknowledged = new AtomicBoolean(false);
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

        private boolean isReady() {
            return open.get() && snapshotAcknowledged.get() && session.isActive(System.currentTimeMillis());
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
