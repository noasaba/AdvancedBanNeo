package me.leoko.advancedban.bukkit.listener;

import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Selects the modern Paper event listener without linking BukkitMain to Paper-only classes. */
public final class ChatListenerRegistrar {
    private static final String MODERN_CHAT_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final String PAPER_LISTENER = "me.leoko.advancedban.bukkit.listener.PaperChatListener";

    private ChatListenerRegistrar() { }

    public static void register(PluginManager manager, Plugin plugin, ClassLoader pluginLoader) {
        try {
            Class.forName(MODERN_CHAT_EVENT, false, pluginLoader);
        } catch (ClassNotFoundException unavailable) {
            manager.registerEvents(new ChatListener(), plugin);
            return;
        }

        try {
            Object listener = Class.forName(PAPER_LISTENER, true, pluginLoader)
                    .getDeclaredConstructor().newInstance();
            if (!(listener instanceof Listener)) {
                throw new IllegalStateException("Paper chat listener does not implement Bukkit Listener");
            }
            manager.registerEvents((Listener) listener, plugin);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Paper AsyncChatEvent is present but its AdvancedBan listener "
                    + "could not be loaded", failure);
        }
    }
}
