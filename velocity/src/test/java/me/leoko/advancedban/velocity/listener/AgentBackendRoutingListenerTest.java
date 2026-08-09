package me.leoko.advancedban.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBackendRoutingListenerTest {
    @Test
    void gateRunsAfterRedirectingPluginsSoTheFinalBackendCannotBypassIt() throws Exception {
        Method listener = AgentBackendRoutingListener.class.getMethod(
                "onServerPreConnect", ServerPreConnectEvent.class);
        assertTrue(listener.getAnnotation(Subscribe.class).order() == PostOrder.LAST);
    }

    @Test
    void configuredAuthorityModeDeniesBackendUntilMatchingAgentAckedSnapshot() {
        AtomicInteger feedback = new AtomicInteger();
        Player player = proxy(Player.class, (method, arguments) -> {
            if (method.getName().equals("sendMessage")) {
                feedback.incrementAndGet();
            }
            return defaultValue(method.getReturnType());
        });
        RegisteredServer survival = registeredServer("survival");
        ServerPreConnectEvent unavailable = new ServerPreConnectEvent(player, survival);
        AgentBackendRoutingListener gate = new AgentBackendRoutingListener(() -> true,
                node -> false);

        gate.onServerPreConnect(unavailable);

        assertFalse(unavailable.getResult().isAllowed());
        assertTrue(feedback.get() == 1, "routing denial should produce one actionable message");

        ServerPreConnectEvent ready = new ServerPreConnectEvent(player, survival);
        new AgentBackendRoutingListener(() -> true, "survival"::equals).onServerPreConnect(ready);
        assertTrue(ready.getResult().isAllowed());
    }

    @Test
    void backwardCompatibleDisabledModeDoesNotRequireAgents() {
        ServerPreConnectEvent event = new ServerPreConnectEvent(
                proxy(Player.class, (method, arguments) -> defaultValue(method.getReturnType())),
                registeredServer("legacy-paper"));

        new AgentBackendRoutingListener(() -> false, node -> false).onServerPreConnect(event);

        assertTrue(event.getResult().isAllowed());
    }

    private static RegisteredServer registeredServer(String name) {
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", 25565));
        return proxy(RegisteredServer.class, (method, arguments) ->
                method.getName().equals("getServerInfo") ? info : defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.invoke(method, arguments));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == java.util.Optional.class) return java.util.Optional.empty();
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
