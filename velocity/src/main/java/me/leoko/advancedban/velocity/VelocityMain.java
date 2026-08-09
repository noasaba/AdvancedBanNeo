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

import java.nio.file.Path;

@Plugin(
        id = "advancedban",
        name = "AdvancedBan",
        version = "2.3.0",
        description = "Advanced punishment system",
        url = "https://github.com/DevLeoko/AdvancedBan",
        authors = {"Leoko"},
        dependencies = {@Dependency(id = "luckperms", optional = true)}
)
public final class VelocityMain {
    private final ProxyServer proxy;
    private final Path dataDirectory;
    private boolean initialized;

    @Inject
    public VelocityMain(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            Universal.get().setup(new VelocityMethods(this, proxy, dataDirectory));
            proxy.getEventManager().register(this, new ConnectionListenerVelocity());
            proxy.getEventManager().register(this, new PlayerInputListenerVelocity());
            initialized = true;
        } catch (RuntimeException | Error exception) {
            if (DatabaseManager.get().isConnectionValid()) {
                DatabaseManager.get().shutdown();
            }
            throw exception;
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (initialized) {
            Universal.get().shutdown();
        }
    }

    public ProxyServer getProxy() {
        return proxy;
    }
}
