package me.leoko.advancedban.bukkit;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bukkit.integration.chatsyncer.ChatSyncerIntegration;
import me.leoko.advancedban.bukkit.listener.ChatListener;
import me.leoko.advancedban.bukkit.listener.PaperChatListener;
import me.leoko.advancedban.compatibility.SignedChatCompatibility;
import org.bukkit.plugin.Plugin;
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
        Plugin signed = getServer().getPluginManager().getPlugin("SignedVelocity");
        if (network.isAgent() || signed != null) {
            String warning = SignedChatCompatibility.warning(
                    signed != null && signed.isEnabled() ? signed.getDescription().getVersion() : null);
            if (warning != null) {
                getLogger().warning(warning);
            } else {
                getLogger().info("SignedVelocity " + signed.getDescription().getVersion()
                        + " detected locally; verify matching Proxy and EVERY Paper backend.");
            }
        }
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
        registerChatListener();
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

    private void registerChatListener() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent", false, getClass().getClassLoader());
            getServer().getPluginManager().registerEvents(
                    new PaperChatListener(), this);
        } catch (ClassNotFoundException unavailable) {
            getServer().getPluginManager().registerEvents(new ChatListener(), this);
        }
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
