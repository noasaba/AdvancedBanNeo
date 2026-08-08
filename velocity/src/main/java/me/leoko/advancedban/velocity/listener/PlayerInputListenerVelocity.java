package me.leoko.advancedban.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import me.leoko.advancedban.Universal;

public final class PlayerInputListenerVelocity {
    @Subscribe(priority = 100)
    public EventTask onChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            if (Universal.get().getMethods().callChat(event.getPlayer())) {
                event.setResult(PlayerChatEvent.ChatResult.denied());
            }
        });
    }

    @Subscribe(priority = 100)
    public EventTask onCommand(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player) || !event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            if (Universal.get().getMethods().callCMD(event.getCommandSource(), "/" + event.getCommand())) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
            }
        });
    }
}
