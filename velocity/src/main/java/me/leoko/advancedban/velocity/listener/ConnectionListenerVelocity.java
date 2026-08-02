package me.leoko.advancedban.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.UUIDManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ConnectionListenerVelocity {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Subscribe(priority = 100)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            try {
                UUIDManager.get().supplyInternUUID(event.getPlayer().getUsername(), event.getPlayer().getUniqueId());
                String address = event.getPlayer().getRemoteAddress().getAddress().getHostAddress();
                String result = Universal.get().callConnection(event.getPlayer().getUsername(), address);
                if (result != null) {
                    event.setResult(ResultedEvent.ComponentResult.denied(LEGACY.deserialize(result)));
                }
            } catch (RuntimeException exception) {
                Universal.get().log("Failed to load player data during Velocity login.");
                Universal.get().debugException(exception);
                if (Universal.get().getMethods().getBoolean(
                        Universal.get().getMethods().getConfig(), "LockdownOnError", true)) {
                    event.setResult(ResultedEvent.ComponentResult.denied(
                            LEGACY.deserialize("[AdvancedBan] Failed to load player data!")));
                }
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Universal.get().getMethods().runAsync(() ->
                PunishmentManager.get().discard(event.getPlayer().getUsername()));
    }
}
