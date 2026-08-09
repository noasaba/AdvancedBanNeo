package me.leoko.advancedban.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.leoko.advancedban.velocity.network.VelocityCoordinatorServer;
import net.kyori.adventure.text.Component;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Prevents a player from reaching a backend whose fail-closed Agent state is unavailable. */
public final class AgentBackendRoutingListener {
    private static final Component UNAVAILABLE = Component.text(
            "[AdvancedBan] Backend punishment enforcement is not ready; please try again shortly.");

    private final BooleanSupplier routingEnforced;
    private final Predicate<String> agentReady;

    public AgentBackendRoutingListener(VelocityCoordinatorServer coordinator) {
        this(coordinator::isAgentRoutingEnforced, coordinator::isAgentReady);
    }

    AgentBackendRoutingListener(BooleanSupplier routingEnforced, Predicate<String> agentReady) {
        this.routingEnforced = routingEnforced;
        this.agentReady = agentReady;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!routingEnforced.getAsBoolean() || !event.getResult().isAllowed()) {
            return;
        }
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (!target.isPresent()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(UNAVAILABLE);
            return;
        }
        String backendName = target.get().getServerInfo().getName();
        if (!agentReady.test(backendName)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(UNAVAILABLE);
        }
    }
}
