package me.leoko.advancedban.bukkit.integration.chatsyncer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveChatSyncerHookTest {

    @Test
    void bothPublicGatesUseTheSameUuidDecisionAndCloseCleanly() throws Exception {
        UUID mutedPlayer = UUID.randomUUID();
        AtomicInteger decisions = new AtomicInteger();
        MuteGate gate = playerId -> {
            decisions.incrementAndGet();
            return mutedPlayer.equals(playerId)
                    ? MuteGate.Decision.reject("muted-layout")
                    : MuteGate.Decision.allow();
        };
        FixtureEventBus eventBus = new FixtureEventBus();
        FixtureRegistry registry = new FixtureRegistry(true);
        FixtureBootstrap bootstrap = new FixtureBootstrap(registry);
        List<String> warnings = new ArrayList<>();
        ReflectiveChatSyncerHook hook = new ReflectiveChatSyncerHook("AdvancedBan", gate, warnings::add);

        assertTrue(hook.open(
                getClass().getClassLoader(),
                service -> service == FixtureEventBusApi.class ? eventBus
                        : service == FixtureBootstrapApi.class ? bootstrap : null,
                legacyApi(),
                vNextApi()));

        assertEquals("AdvancedBan", eventBus.pluginName);
        assertNotNull(eventBus.listener);
        FixtureMessageDecision legacyMuted = eventBus.listener.beforeSend(
                new FixtureMessageContext(FixtureOrigin.MINECRAFT, Optional.of(mutedPlayer)));
        assertTrue(legacyMuted.cancelled());
        assertEquals("muted-layout", legacyMuted.reason());

        FixtureMessageDecision legacyAllowed = eventBus.listener.beforeSend(
                new FixtureMessageContext(FixtureOrigin.MINECRAFT, Optional.of(UUID.randomUUID())));
        assertFalse(legacyAllowed.cancelled());

        FixturePreSendDecision vNextMuted = registry.interceptor.intercept(
                        new FixturePreSendEvent(new FixtureSnapshot(new FixtureActor(Optional.of(mutedPlayer)))))
                .toCompletableFuture().get();
        assertFalse(vNextMuted.allowed());
        assertEquals("advancedban", vNextMuted.error().get().namespace);
        assertEquals("MUTED", vNextMuted.error().get().code);
        assertEquals("muted-layout", vNextMuted.safeDetail());

        FixturePreSendDecision vNextAllowed = registry.interceptor.intercept(
                        new FixturePreSendEvent(new FixtureSnapshot(new FixtureActor(Optional.of(UUID.randomUUID())))))
                .toCompletableFuture().get();
        assertTrue(vNextAllowed.allowed());

        assertEquals("advancedban:mute-gate", registry.options.key);
        assertEquals(-1000, registry.options.priority);
        assertEquals(Duration.ofMillis(100), registry.options.timeout);
        assertEquals(FixtureFailurePolicy.REJECT, registry.options.failurePolicy);
        assertEquals(new HashSet<>(Arrays.asList(FixtureMessageKind.PLAYER, FixtureMessageKind.PRIVATE)),
                registry.options.messageKinds);
        assertTrue(registry.options.channelScopes.isEmpty());
        assertEquals(4, decisions.get());
        assertTrue(warnings.isEmpty());

        hook.close();
        hook.close();
        assertEquals("AdvancedBan", eventBus.unregisteredPlugin);
        assertTrue(registry.registration.closed);
    }

    @Test
    void messagesWithoutMinecraftUuidAreAllowedWithoutConsultingMuteState() throws Exception {
        AtomicInteger decisions = new AtomicInteger();
        MuteGate gate = playerId -> {
            decisions.incrementAndGet();
            return MuteGate.Decision.reject("must-not-run");
        };
        FixtureEventBus eventBus = new FixtureEventBus();
        FixtureRegistry registry = new FixtureRegistry(true);
        ReflectiveChatSyncerHook hook = new ReflectiveChatSyncerHook("AdvancedBan", gate, message -> { });
        hook.open(getClass().getClassLoader(),
                service -> service == FixtureEventBusApi.class ? eventBus
                        : service == FixtureBootstrapApi.class ? new FixtureBootstrap(registry) : null,
                legacyApi(), vNextApi());

        assertFalse(eventBus.listener.beforeSend(
                new FixtureMessageContext(FixtureOrigin.SYSTEM, Optional.empty())).cancelled());
        FixturePreSendDecision vNext = registry.interceptor.intercept(
                        new FixturePreSendEvent(new FixtureSnapshot(new FixtureActor(Optional.empty()))))
                .toCompletableFuture().get();
        assertTrue(vNext.allowed());
        assertEquals(0, decisions.get());
        hook.close();
    }

    @Test
    void missingRuntimeApiLeavesAdvancedBanOperational() {
        List<String> warnings = new ArrayList<>();
        ReflectiveChatSyncerHook hook = new ReflectiveChatSyncerHook(
                "AdvancedBan", player -> MuteGate.Decision.allow(), warnings::add);
        ClassLoader emptyLoader = new ClassLoader(null) { };

        assertFalse(hook.open(emptyLoader, service -> null));
        assertEquals(2, warnings.size());
        hook.close();
    }

    @Test
    void rejectedVNextRegistrationRollsBackLegacyGate() throws Exception {
        FixtureEventBus eventBus = new FixtureEventBus();
        FixtureRegistry registry = new FixtureRegistry(false);
        List<String> warnings = new ArrayList<>();
        ReflectiveChatSyncerHook hook = new ReflectiveChatSyncerHook(
                "AdvancedBan", player -> MuteGate.Decision.allow(), warnings::add);

        assertFalse(hook.open(getClass().getClassLoader(),
                service -> service == FixtureEventBusApi.class ? eventBus
                        : service == FixtureBootstrapApi.class ? new FixtureBootstrap(registry) : null,
                legacyApi(), vNextApi()));
        assertEquals("AdvancedBan", eventBus.unregisteredPlugin);
        assertTrue(warnings.stream().anyMatch(message -> message.contains("rejected")));
        hook.close();
    }

    @Test
    void legacyGateAllowsDiscordEvenWhenItCarriesAPlayerUuid() throws Exception {
        AtomicInteger decisions = new AtomicInteger();
        FixtureEventBus eventBus = new FixtureEventBus();
        ReflectiveChatSyncerHook hook = new ReflectiveChatSyncerHook(
                "AdvancedBan", player -> {
                    decisions.incrementAndGet();
                    return MuteGate.Decision.reject("muted");
                }, message -> { });
        assertTrue(hook.open(getClass().getClassLoader(),
                service -> service == FixtureEventBusApi.class ? eventBus
                        : service == FixtureBootstrapApi.class
                        ? new FixtureBootstrap(new FixtureRegistry(true)) : null,
                legacyApi(), vNextApi()));

        assertFalse(eventBus.listener.beforeSend(new FixtureMessageContext(
                FixtureOrigin.DISCORD, Optional.of(UUID.randomUUID()))).cancelled());
        assertEquals(0, decisions.get());
        hook.close();
    }

    private static ReflectiveChatSyncerHook.LegacyApi legacyApi() throws NoSuchMethodException {
        return new ReflectiveChatSyncerHook.LegacyApi(
                FixtureEventBusApi.class, FixturePreSendListener.class,
                FixtureMessageContext.class, FixtureMessageDecision.class);
    }

    private static ReflectiveChatSyncerHook.VNextApi vNextApi() throws NoSuchMethodException {
        return new ReflectiveChatSyncerHook.VNextApi(
                FixtureBootstrapApi.class,
                FixtureClientApi.class,
                FixtureExtensionRegistry.class,
                FixtureRequestId.class,
                FixtureRequestContext.class,
                FixtureOptions.class,
                FixtureFailurePolicy.class,
                FixtureMessageKind.class,
                FixtureInterceptor.class,
                FixtureOperationError.class,
                FixturePreSendDecision.class,
                FixturePreSendEvent.class,
                FixtureSnapshot.class,
                FixtureActor.class);
    }

    public interface FixtureEventBusApi {
        void registerPreSendListener(String pluginName, FixturePreSendListener listener);
        void unregisterByPlugin(String pluginName);
    }

    public interface FixturePreSendListener {
        FixtureMessageDecision beforeSend(FixtureMessageContext context);
    }

    public static final class FixtureEventBus implements FixtureEventBusApi {
        String pluginName;
        String unregisteredPlugin;
        FixturePreSendListener listener;

        @Override
        public void registerPreSendListener(String pluginName, FixturePreSendListener listener) {
            this.pluginName = pluginName;
            this.listener = listener;
        }

        @Override
        public void unregisterByPlugin(String pluginName) {
            this.unregisteredPlugin = pluginName;
            this.listener = null;
        }
    }

    public static final class FixtureMessageContext {
        private final FixtureOrigin origin;
        private final Optional<UUID> authorId;

        FixtureMessageContext(FixtureOrigin origin, Optional<UUID> authorId) {
            this.origin = origin;
            this.authorId = authorId;
        }

        public FixtureOrigin origin() {
            return origin;
        }

        public Optional<UUID> authorId() {
            return authorId;
        }
    }

    public enum FixtureOrigin {
        MINECRAFT, DISCORD, VELOCITY, PRIVATE_MESSAGE, SYSTEM
    }

    public static final class FixtureMessageDecision {
        private final boolean cancelled;
        private final String reason;

        private FixtureMessageDecision(boolean cancelled, String reason) {
            this.cancelled = cancelled;
            this.reason = reason;
        }

        public static FixtureMessageDecision allow() {
            return new FixtureMessageDecision(false, "");
        }

        public static FixtureMessageDecision cancel(String reason) {
            return new FixtureMessageDecision(true, reason);
        }

        public boolean cancelled() {
            return cancelled;
        }

        public String reason() {
            return reason;
        }
    }

    public interface FixtureBootstrapApi {
        FixtureClientApi client();
    }

    public interface FixtureClientApi {
        FixtureExtensionRegistry extensions();
    }

    public interface FixtureExtensionRegistry {
        FixtureOperationResult registerPreSendInterceptor(
                FixtureRequestContext context, FixtureOptions options, FixtureInterceptor interceptor);
    }

    public interface FixtureInterceptor {
        CompletionStage<FixturePreSendDecision> intercept(FixturePreSendEvent event);
    }

    public interface Registration {
        void close();
    }

    public static final class FixtureRegistration implements Registration {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    public static final class FixtureBootstrap implements FixtureBootstrapApi, FixtureClientApi {
        private final FixtureExtensionRegistry extensions;

        FixtureBootstrap(FixtureExtensionRegistry extensions) {
            this.extensions = extensions;
        }

        @Override
        public FixtureClientApi client() {
            return this;
        }

        @Override
        public FixtureExtensionRegistry extensions() {
            return extensions;
        }
    }

    public static final class FixtureRegistry implements FixtureExtensionRegistry {
        final boolean accept;
        final FixtureRegistration registration = new FixtureRegistration();
        FixtureOptions options;
        FixtureInterceptor interceptor;

        FixtureRegistry(boolean accept) {
            this.accept = accept;
        }

        @Override
        public FixtureOperationResult registerPreSendInterceptor(
                FixtureRequestContext context, FixtureOptions options, FixtureInterceptor interceptor) {
            this.options = options;
            this.interceptor = interceptor;
            return accept
                    ? new FixtureOperationResult(true, Optional.of(registration), "accepted")
                    : new FixtureOperationResult(false, Optional.empty(), "fixture rejection");
        }
    }

    public static final class FixtureRequestId {
        public static FixtureRequestId create() {
            return new FixtureRequestId();
        }
    }

    public static final class FixtureRequestContext {
        public static FixtureRequestContext basic(FixtureRequestId requestId) {
            return new FixtureRequestContext();
        }
    }

    public enum FixtureFailurePolicy { CONTINUE, REJECT }
    public enum FixtureMessageKind { PLAYER, SYSTEM, PRIVATE, NETWORK }

    public static final class FixtureOptions {
        final String key;
        final int priority;
        final Duration timeout;
        final FixtureFailurePolicy failurePolicy;
        final Set<?> messageKinds;
        final Set<?> channelScopes;

        public FixtureOptions(String key, int priority, Duration timeout,
                              FixtureFailurePolicy failurePolicy, Set<?> messageKinds, Set<?> channelScopes) {
            this.key = key;
            this.priority = priority;
            this.timeout = timeout;
            this.failurePolicy = failurePolicy;
            this.messageKinds = messageKinds;
            this.channelScopes = channelScopes;
        }
    }

    public static final class FixtureOperationResult {
        private final boolean success;
        private final Optional<FixtureRegistration> value;
        private final String safeDetail;

        FixtureOperationResult(boolean success, Optional<FixtureRegistration> value, String safeDetail) {
            this.success = success;
            this.value = value;
            this.safeDetail = safeDetail;
        }

        public boolean success() {
            return success;
        }

        public Optional<FixtureRegistration> value() {
            return value;
        }

        public String safeDetail() {
            return safeDetail;
        }
    }

    public static final class FixtureActor {
        private final Optional<UUID> playerId;

        FixtureActor(Optional<UUID> playerId) {
            this.playerId = playerId;
        }

        public Optional<UUID> playerId() {
            return playerId;
        }
    }

    public static final class FixtureSnapshot {
        private final FixtureActor actor;

        FixtureSnapshot(FixtureActor actor) {
            this.actor = actor;
        }

        public FixtureActor actor() {
            return actor;
        }
    }

    public static final class FixturePreSendEvent {
        private final FixtureSnapshot snapshot;

        FixturePreSendEvent(FixtureSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        public FixtureSnapshot snapshot() {
            return snapshot;
        }
    }

    public static final class FixtureOperationError {
        final String namespace;
        final String code;

        public FixtureOperationError(String namespace, String code) {
            this.namespace = namespace;
            this.code = code;
        }
    }

    public static final class FixturePreSendDecision {
        private final boolean allowed;
        private final Optional<FixtureOperationError> error;
        private final String safeDetail;

        private FixturePreSendDecision(boolean allowed, Optional<FixtureOperationError> error, String safeDetail) {
            this.allowed = allowed;
            this.error = error;
            this.safeDetail = safeDetail;
        }

        public static FixturePreSendDecision allow() {
            return new FixturePreSendDecision(true, Optional.empty(), "");
        }

        public static FixturePreSendDecision reject(FixtureOperationError error, String safeDetail) {
            return new FixturePreSendDecision(false, Optional.of(error), safeDetail);
        }

        public boolean allowed() {
            return allowed;
        }

        public Optional<FixtureOperationError> error() {
            return error;
        }

        public String safeDetail() {
            return safeDetail;
        }
    }
}
