package me.leoko.advancedban.bukkit.integration.chatsyncer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional ChatSyncer public-API adapter.
 *
 * <p>ChatSyncer 0.2.0 beta API artifacts are intentionally not linked or
 * bundled. All reflection in this class is limited to the documented public
 * API loaded from the enabled ChatSyncerChat plugin.</p>
 */
final class ReflectiveChatSyncerHook implements AutoCloseable {
    private static final String API_PACKAGE = "dev.chatsyncer.api.";
    private static final String CHAT_API_PACKAGE = "dev.chatsyncer.chat.api.";
    private static final String EXTENSION_KEY = "advancedban:mute-gate";

    interface ServiceLookup {
        Object load(Class<?> serviceType);
    }

    private final String pluginName;
    private final MuteGate muteGate;
    private final Consumer<String> warningLogger;

    private Object eventBus;
    private Method unregisterByPlugin;
    private Object eventBusListener;
    private Object preSendRegistration;
    private boolean closed;

    ReflectiveChatSyncerHook(String pluginName, MuteGate muteGate, Consumer<String> warningLogger) {
        this.pluginName = pluginName;
        this.muteGate = muteGate;
        this.warningLogger = warningLogger;
    }

    boolean open(ClassLoader apiLoader, ServiceLookup services) {
        boolean legacy = registerEventBus(apiLoader, services);
        boolean vNext = registerVNext(apiLoader, services);
        return finishOpen(legacy, vNext);
    }

    boolean open(ClassLoader proxyLoader, ServiceLookup services, LegacyApi legacyApi, VNextApi vNextApi) {
        boolean legacy = registerEventBus(proxyLoader, services, legacyApi);
        boolean vNext = registerVNext(proxyLoader, services, vNextApi);
        return finishOpen(legacy, vNext);
    }

    private boolean finishOpen(boolean legacy, boolean vNext) {
        if (legacy && vNext) {
            return true;
        }
        if (legacy || vNext) {
            warningLogger.accept("ChatSyncer exposed only part of its required public mute pipeline; "
                    + "the incomplete hook was rolled back.");
        }
        close();
        return false;
    }

    private boolean registerEventBus(ClassLoader loader, ServiceLookup services) {
        try {
            LegacyApi api = LegacyApi.load(loader);
            return registerEventBus(loader, services, api);
        } catch (ClassNotFoundException exception) {
            warningLogger.accept("ChatSyncer ChatEventBus public API is unavailable; compatibility gate was not registered.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warningLogger.accept("Unable to register the ChatSyncer ChatEventBus mute gate: " + failureName(exception));
        }
        return false;
    }

    private boolean registerEventBus(ClassLoader loader, ServiceLookup services, LegacyApi api) {
        try {
            Object loadedEventBus = services.load(api.eventBus);
            if (loadedEventBus == null) {
                warningLogger.accept("ChatSyncer ChatEventBus service is unavailable; compatibility gate was not registered.");
                return false;
            }

            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{api.preSendListener},
                    new LegacyInvocationHandler(api, muteGate));
            api.eventBus.getMethod("registerPreSendListener", String.class, api.preSendListener)
                    .invoke(loadedEventBus, pluginName, listener);

            eventBus = loadedEventBus;
            eventBusListener = listener;
            unregisterByPlugin = api.eventBus.getMethod("unregisterByPlugin", String.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warningLogger.accept("Unable to register the ChatSyncer ChatEventBus mute gate: " + failureName(exception));
            return false;
        }
    }

    private boolean registerVNext(ClassLoader loader, ServiceLookup services) {
        try {
            VNextApi api = VNextApi.load(loader);
            return registerVNext(loader, services, api);
        } catch (ClassNotFoundException exception) {
            warningLogger.accept("ChatSyncer vNext public API is unavailable; interceptor was not registered.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warningLogger.accept("Unable to register the ChatSyncer vNext mute gate: " + failureName(exception));
        }
        return false;
    }

    private boolean registerVNext(ClassLoader loader, ServiceLookup services, VNextApi api) {
        try {
            Object bootstrap = services.load(api.bootstrap);
            if (bootstrap == null) {
                warningLogger.accept("ChatSyncer vNext bootstrap service is unavailable; interceptor was not registered.");
                return false;
            }

            Object client = api.bootstrap.getMethod("client").invoke(bootstrap);
            Object extensions = api.chatClient.getMethod("extensions").invoke(client);
            Object options = api.optionsConstructor.newInstance(
                    EXTENSION_KEY,
                    -1000,
                    Duration.ofMillis(100),
                    enumValue(api.failurePolicy, "REJECT"),
                    messageKinds(api.messageKind),
                    Collections.emptySet());
            Object requestId = api.requestId.getMethod("create").invoke(null);
            Object requestContext = api.requestContext.getMethod("basic", api.requestId).invoke(null, requestId);
            Object interceptor = Proxy.newProxyInstance(loader, new Class<?>[]{api.preSendInterceptor},
                    new VNextInvocationHandler(api, muteGate));

            Object result = api.extensionRegistry.getMethod("registerPreSendInterceptor",
                            api.requestContext, api.extensionOptions, api.preSendInterceptor)
                    .invoke(extensions, requestContext, options, interceptor);
            boolean success = Boolean.TRUE.equals(result.getClass().getMethod("success").invoke(result));
            if (!success) {
                Object detail = result.getClass().getMethod("safeDetail").invoke(result);
                warningLogger.accept("ChatSyncer rejected the AdvancedBan vNext mute gate: " + String.valueOf(detail));
                return false;
            }

            Object value = result.getClass().getMethod("value").invoke(result);
            if (!(value instanceof Optional) || !((Optional<?>) value).isPresent()) {
                warningLogger.accept("ChatSyncer accepted the vNext mute gate without returning a registration handle.");
                return false;
            }
            preSendRegistration = ((Optional<?>) value).get();
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warningLogger.accept("Unable to register the ChatSyncer vNext mute gate: " + failureName(exception));
        }
        return false;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        closeRegistration();
        if (eventBus != null && unregisterByPlugin != null) {
            try {
                unregisterByPlugin.invoke(eventBus, pluginName);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                warningLogger.accept("Unable to unregister the ChatSyncer ChatEventBus mute gate: "
                        + failureName(exception));
            }
        }
        eventBusListener = null;
        eventBus = null;
        unregisterByPlugin = null;
    }

    private void closeRegistration() {
        if (preSendRegistration == null) {
            return;
        }
        try {
            Method close = findPublicClose(preSendRegistration);
            close.invoke(preSendRegistration);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            warningLogger.accept("Unable to close the ChatSyncer vNext mute gate: " + failureName(exception));
        } finally {
            preSendRegistration = null;
        }
    }

    private static Method findPublicClose(Object registration) throws NoSuchMethodException {
        for (Class<?> type : registration.getClass().getInterfaces()) {
            if ("dev.chatsyncer.api.Registration".equals(type.getName())
                    || "Registration".equals(type.getSimpleName())) {
                return type.getMethod("close");
            }
        }
        return registration.getClass().getMethod("close");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> enumType, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumType.asSubclass(Enum.class), name);
    }

    private static Set<Object> messageKinds(Class<?> enumType) {
        Set<Object> kinds = new LinkedHashSet<>();
        kinds.add(enumValue(enumType, "PLAYER"));
        kinds.add(enumValue(enumType, "PRIVATE"));
        return Collections.unmodifiableSet(kinds);
    }

    private static String failureName(Throwable failure) {
        Throwable current = failure;
        if (failure instanceof InvocationTargetException
                && ((InvocationTargetException) failure).getTargetException() != null) {
            current = ((InvocationTargetException) failure).getTargetException();
        }
        return current.getClass().getSimpleName();
    }

    private static UUID optionalUuid(Object value) {
        if (!(value instanceof Optional)) {
            return null;
        }
        Object playerId = ((Optional<?>) value).orElse(null);
        return playerId instanceof UUID ? (UUID) playerId : null;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        switch (method.getName()) {
            case "toString":
                return label;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            default:
                throw new UnsupportedOperationException(method.getName());
        }
    }

    private static final class LegacyInvocationHandler implements InvocationHandler {
        private final LegacyApi api;
        private final MuteGate gate;

        private LegacyInvocationHandler(LegacyApi api, MuteGate gate) {
            this.api = api;
            this.gate = gate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "AdvancedBan ChatSyncer pre-send listener");
            }
            if (!"beforeSend".equals(method.getName()) || args == null || args.length != 1) {
                throw new UnsupportedOperationException(method.getName());
            }

            Object authorId = api.authorId.invoke(args[0]);
            UUID playerId = optionalUuid(authorId);
            Object origin = api.origin.invoke(args[0]);
            String originName = origin instanceof Enum ? ((Enum<?>) origin).name() : String.valueOf(origin);
            if (playerId == null || !("MINECRAFT".equals(originName)
                    || "PRIVATE_MESSAGE".equals(originName))) {
                return api.allow.invoke(null);
            }
            MuteGate.Decision decision = gate.evaluate(playerId);
            return decision.isAllowed()
                    ? api.allow.invoke(null)
                    : api.cancel.invoke(null, decision.getReason());
        }
    }

    private static final class VNextInvocationHandler implements InvocationHandler {
        private final VNextApi api;
        private final MuteGate gate;

        private VNextInvocationHandler(VNextApi api, MuteGate gate) {
            this.api = api;
            this.gate = gate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "AdvancedBan ChatSyncer vNext interceptor");
            }
            if (!"intercept".equals(method.getName()) || args == null || args.length != 1) {
                throw new UnsupportedOperationException(method.getName());
            }

            Object snapshot = api.snapshot.invoke(args[0]);
            Object actor = api.actor.invoke(snapshot);
            UUID playerId = optionalUuid(api.playerId.invoke(actor));
            Object decision;
            if (playerId == null) {
                decision = api.allow.invoke(null);
            } else {
                MuteGate.Decision muteDecision = gate.evaluate(playerId);
                if (muteDecision.isAllowed()) {
                    decision = api.allow.invoke(null);
                } else {
                    Object error = api.errorConstructor.newInstance("advancedban", "MUTED");
                    decision = api.reject.invoke(null, error, muteDecision.getReason());
                }
            }
            return CompletableFuture.completedFuture(decision);
        }
    }

    static final class LegacyApi {
        final Class<?> eventBus;
        final Class<?> preSendListener;
        final Class<?> messageDecision;
        final Method origin;
        final Method authorId;
        final Method allow;
        final Method cancel;

        LegacyApi(Class<?> eventBus, Class<?> preSendListener, Class<?> messageContext,
                  Class<?> messageDecision) throws NoSuchMethodException {
            this.eventBus = eventBus;
            this.preSendListener = preSendListener;
            this.messageDecision = messageDecision;
            this.origin = messageContext.getMethod("origin");
            this.authorId = messageContext.getMethod("authorId");
            this.allow = messageDecision.getMethod("allow");
            this.cancel = messageDecision.getMethod("cancel", String.class);
        }

        static LegacyApi load(ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
            return new LegacyApi(
                    Class.forName(CHAT_API_PACKAGE + "ChatEventBus", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatPreSendListener", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatMessageContext", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatMessageDecision", true, loader));
        }
    }

    static final class VNextApi {
        final Class<?> bootstrap;
        final Class<?> chatClient;
        final Class<?> extensionRegistry;
        final Class<?> requestId;
        final Class<?> requestContext;
        final Class<?> extensionOptions;
        final Class<?> failurePolicy;
        final Class<?> messageKind;
        final Class<?> preSendInterceptor;
        final Class<?> operationError;
        final Class<?> preSendDecision;
        final Constructor<?> optionsConstructor;
        final Constructor<?> errorConstructor;
        final Method snapshot;
        final Method actor;
        final Method playerId;
        final Method allow;
        final Method reject;

        VNextApi(Class<?> bootstrap, Class<?> chatClient, Class<?> extensionRegistry, Class<?> requestId,
                 Class<?> requestContext, Class<?> extensionOptions, Class<?> failurePolicy,
                 Class<?> messageKind, Class<?> preSendInterceptor, Class<?> operationError,
                 Class<?> preSendDecision, Class<?> preSendEvent, Class<?> messageSnapshot,
                 Class<?> actorType) throws NoSuchMethodException {
            this.bootstrap = bootstrap;
            this.chatClient = chatClient;
            this.extensionRegistry = extensionRegistry;
            this.requestId = requestId;
            this.requestContext = requestContext;
            this.extensionOptions = extensionOptions;
            this.failurePolicy = failurePolicy;
            this.messageKind = messageKind;
            this.preSendInterceptor = preSendInterceptor;
            this.operationError = operationError;
            this.preSendDecision = preSendDecision;
            this.optionsConstructor = extensionOptions.getConstructor(String.class, int.class, Duration.class,
                    failurePolicy, Set.class, Set.class);
            this.errorConstructor = operationError.getConstructor(String.class, String.class);
            this.snapshot = preSendEvent.getMethod("snapshot");
            this.actor = messageSnapshot.getMethod("actor");
            this.playerId = actorType.getMethod("playerId");
            this.allow = preSendDecision.getMethod("allow");
            this.reject = preSendDecision.getMethod("reject", operationError, String.class);
        }

        static VNextApi load(ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
            return new VNextApi(
                    Class.forName(CHAT_API_PACKAGE + "ChatApiBootstrap", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatClient", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatExtensionRegistry", true, loader),
                    Class.forName(API_PACKAGE + "RequestId", true, loader),
                    Class.forName(API_PACKAGE + "RequestContext", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ExtensionRegistrationOptions", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ExtensionFailurePolicy", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatMessageKind", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatPreSendInterceptor", true, loader),
                    Class.forName(API_PACKAGE + "OperationError", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "PreSendDecision", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatPreSendEvent", true, loader),
                    Class.forName(CHAT_API_PACKAGE + "ChatMessageSnapshot", true, loader),
                    Class.forName(API_PACKAGE + "Actor", true, loader));
        }
    }
}
