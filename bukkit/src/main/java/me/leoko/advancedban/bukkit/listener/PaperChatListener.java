package me.leoko.advancedban.bukkit.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bukkit.BukkitMain;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Loaded only on Paper with the modern chat API; preserves SignedVelocity's LOWEST decision. */
public final class PaperChatListener implements Listener {
    private final BooleanSupplier chatSyncerActive;
    private final Predicate<Player> denyChat;

    public PaperChatListener() {
        this(() -> BukkitMain.get().isChatSyncerMuteGateActive(),
                player -> Universal.get().getMethods().callChat(player));
    }

    PaperChatListener(BooleanSupplier chatSyncerActive, Predicate<Player> denyChat) {
        this.chatSyncerActive = chatSyncerActive;
        this.denyChat = denyChat;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!event.isCancelled() && !chatSyncerActive.getAsBoolean() && denyChat.test(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
