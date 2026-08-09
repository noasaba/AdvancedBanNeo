package me.leoko.advancedban.bukkit;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bukkit.integration.chatsyncer.ChatSyncerIntegration;
import me.leoko.advancedban.bukkit.listener.ChatListener;
import me.leoko.advancedban.bukkit.listener.CommandListener;
import me.leoko.advancedban.bukkit.listener.ConnectionListener;
import me.leoko.advancedban.bukkit.listener.InternalListener;
import me.leoko.advancedban.bukkit.network.PaperAgentClient;
import me.leoko.advancedban.bukkit.network.PaperNetworkSettings;
import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.runtime.RuntimeRole;
import org.bukkit.Bukkit;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitMain extends JavaPlugin {
    private static BukkitMain instance;
    private ChatSyncerIntegration chatSyncerIntegration;
    private PaperAgentClient agentClient;
    private boolean universalInitialized;

    public static BukkitMain get() {
        return instance;
    }

    public boolean isChatSyncerMuteGateActive() {
        return chatSyncerIntegration != null && chatSyncerIntegration.isActive();
    }

    @Override
    public void onEnable() {
        instance = this;
        try {
            enablePlugin();
        } catch (RuntimeException | Error exception) {
            closeRuntime();
            if (DatabaseManager.get().isConnectionValid()) {
                DatabaseManager.get().shutdown();
            }
            throw exception;
        }
    }

    private void enablePlugin() {
        PaperNetworkSettings network = PaperNetworkSettings.load(this);
        BukkitMethods methods = new BukkitMethods(network.isAgent()
                ? RuntimeRole.AGENT_DEGRADED : RuntimeRole.STANDALONE_AUTHORITY);
        Universal.get().setup(methods);
        universalInitialized = true;
        if (network.isAgent()) {
            if (!network.isValidAgent()) {
                Universal.get().log("Paper is configured as an Agent but cannot authenticate: " + network.getError());
            } else {
                agentClient = new PaperAgentClient(network);
                methods.setAgentClient(agentClient);
                agentClient.start();
            }
        }

        ConnectionListener connListener = new ConnectionListener();
        this.getServer().getPluginManager().registerEvents(connListener, this);
        this.getServer().getPluginManager().registerEvents(new ChatListener(), this);
        this.getServer().getPluginManager().registerEvents(new CommandListener(), this);
        this.getServer().getPluginManager().registerEvents(new InternalListener(), this);
        chatSyncerIntegration = new ChatSyncerIntegration(this);
        this.getServer().getPluginManager().registerEvents(chatSyncerIntegration, this);
        chatSyncerIntegration.enableIfAvailable();

        Bukkit.getOnlinePlayers().forEach(player -> {
            AsyncPlayerPreLoginEvent apple = new AsyncPlayerPreLoginEvent(player.getName(), player.getAddress().getAddress(), player.getUniqueId());
            connListener.onConnect(apple);
            if (apple.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_BANNED) {
                player.kickPlayer(apple.getKickMessage());
            }
        });

    }

    @Override
    public void onDisable() {
        closeRuntime();
    }

    private void closeRuntime() {
        if (agentClient != null) {
            agentClient.close();
            agentClient = null;
        }
        if (chatSyncerIntegration != null) {
            chatSyncerIntegration.close();
            chatSyncerIntegration = null;
        }
        if (universalInitialized) {
            Universal.get().shutdown();
            universalInitialized = false;
        }
    }
}
