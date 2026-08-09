package me.leoko.advancedban.bukkit.listener;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bukkit.BukkitMain;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Created by Leoko @ dev.skamps.eu on 16.07.2016.
 */
public class ChatListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        BukkitMain plugin = BukkitMain.get();
        if (plugin != null && plugin.isChatSyncerMuteGateActive()) {
            return;
        }
        if (Universal.get().getMethods().callChat(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
