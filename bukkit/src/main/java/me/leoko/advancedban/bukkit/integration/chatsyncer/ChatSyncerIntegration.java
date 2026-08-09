package me.leoko.advancedban.bukkit.integration.chatsyncer;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the optional ChatSyncer hook and follows its plugin lifecycle. */
public final class ChatSyncerIntegration implements Listener, AutoCloseable {
    static final String CHAT_SYNCER_PLUGIN = "ChatSyncerChat";

    private final JavaPlugin owner;
    private final MuteGate muteGate;
    private ReflectiveChatSyncerHook hook;
    private Plugin hookedPlugin;
    private volatile boolean active;

    public ChatSyncerIntegration(JavaPlugin owner) {
        this(owner, new AdvancedBanMuteGate());
    }

    ChatSyncerIntegration(JavaPlugin owner, MuteGate muteGate) {
        this.owner = owner;
        this.muteGate = muteGate;
    }

    public synchronized void enableIfAvailable() {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin(CHAT_SYNCER_PLUGIN);
        if (plugin != null && plugin.isEnabled()) {
            enable(plugin);
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (isChatSyncer(event.getPlugin())) {
            enable(event.getPlugin());
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == hookedPlugin || isChatSyncer(event.getPlugin())) {
            closeHook();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private synchronized void enable(Plugin chatSyncer) {
        if (chatSyncer == hookedPlugin && hook != null) {
            return;
        }
        closeHook();

        ReflectiveChatSyncerHook candidate = new ReflectiveChatSyncerHook(
                owner.getName(), muteGate, owner.getLogger()::warning);
        boolean registered = candidate.open(
                chatSyncer.getClass().getClassLoader(),
                serviceType -> Bukkit.getServicesManager().load((Class) serviceType));
        if (registered) {
            hook = candidate;
            hookedPlugin = chatSyncer;
            active = true;
            owner.getLogger().info("Enabled ChatSyncer mute compatibility.");
        } else {
            candidate.close();
        }
    }

    private static boolean isChatSyncer(Plugin plugin) {
        return plugin != null && CHAT_SYNCER_PLUGIN.equals(plugin.getName());
    }

    private synchronized void closeHook() {
        active = false;
        if (hook != null) {
            hook.close();
            hook = null;
        }
        hookedPlugin = null;
    }

    @Override
    public void close() {
        closeHook();
    }

    public boolean isActive() {
        return active;
    }
}
