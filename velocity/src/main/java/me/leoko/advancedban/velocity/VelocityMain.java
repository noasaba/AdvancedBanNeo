package me.leoko.advancedban.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.velocity.listener.ConnectionListenerVelocity;
import me.leoko.advancedban.velocity.listener.PlayerInputListenerVelocity;
import me.leoko.advancedban.velocity.network.VelocityCoordinatorServer;
import me.leoko.advancedban.velocity.network.VelocityNetworkSettings;

import java.nio.file.Path;

@Plugin(
        id = "advancedban",
        name = "AdvancedBan Neo",
        version = "2.4.0-beta.1",
        description = "AdvancedBan 2.3.0-compatible punishment system maintained as AdvancedBan Neo",
        url = "https://github.com/noasaba/AdvancedBanNeo",
        authors = {"Leoko", "nanosize (noasaba)"},
        dependencies = {
                @Dependency(id = "luckperms", optional = true),
                @Dependency(id = "floodgate", optional = true)
        }
)
public final class VelocityMain {
    private final ProxyServer proxy;
    private final Path dataDirectory;
    private boolean initialized;
    private VelocityCoordinatorServer coordinator;

    @Inject
    public VelocityMain(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            VelocityMethods methods = new VelocityMethods(this, proxy, dataDirectory);
            Universal.get().setup(methods);
            try {
                VelocityNetworkSettings network = VelocityNetworkSettings.load(
                        dataDirectory, methods.wasConfigCreated());
                Universal.get().log("Running as Velocity Authority.");
                if (!network.isEnabled()) {
                    Universal.get().log("Paper Agent transport is disabled by Network.Enabled.");
                } else {
                    if (network.wasCredentialGenerated()) {
                        Universal.get().log("AdvancedBan Neo network key was generated.");
                        Universal.get().log("Copy the configured KeyFile to every enabled Paper Agent.");
                    } else {
                        Universal.get().log("Network credential loaded.");
                    }
                }
                coordinator = new VelocityCoordinatorServer(proxy, network);
                coordinator.start();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Failed to start the Authority transport", exception);
            }
            proxy.getEventManager().register(this, new ConnectionListenerVelocity());
            proxy.getEventManager().register(this, new PlayerInputListenerVelocity());
            initialized = true;
        } catch (RuntimeException | Error exception) {
            if (coordinator != null) {
                coordinator.close();
                coordinator = null;
            }
            if (DatabaseManager.get().isConnectionValid()) {
                DatabaseManager.get().shutdown();
            }
            throw exception;
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (initialized) {
            if (coordinator != null) {
                coordinator.close();
            }
            Universal.get().shutdown();
        }
    }

    public ProxyServer getProxy() {
        return proxy;
    }

    VelocityCoordinatorServer getCoordinator() {
        return coordinator;
    }
}
