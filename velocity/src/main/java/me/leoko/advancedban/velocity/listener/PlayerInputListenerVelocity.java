package me.leoko.advancedban.velocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import me.leoko.advancedban.Universal;

import java.util.function.Predicate;

public final class PlayerInputListenerVelocity {
    private final Predicate<Player> denyChat;

    public PlayerInputListenerVelocity() {
        this(player -> Universal.get().getMethods().callChat(player));
    }

    PlayerInputListenerVelocity(Predicate<Player> denyChat) {
        this.denyChat = denyChat;
    }

    // SignedVelocity consumes this denial at Short.MIN_VALUE. Keep proxy
    // enforcement active even when no Paper Agent is installed or connected.
    @Subscribe(priority = 100)
    public EventTask onChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> {
            if (denyChat.test(event.getPlayer())) {
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
