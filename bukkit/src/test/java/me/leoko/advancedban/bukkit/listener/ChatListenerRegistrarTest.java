package me.leoko.advancedban.bukkit.listener;

import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatListenerRegistrarTest {
    private static final String PAPER_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";

    @Test
    void legacyBukkitLoaderRegistersOnlyLegacyListener() {
        AtomicReference<Listener> registered = new AtomicReference<>();
        ClassLoader noPaper = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (PAPER_EVENT.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        ChatListenerRegistrar.register(manager(registered), plugin(), noPaper);

        assertInstanceOf(ChatListener.class, registered.get());
    }

    @Test
    void modernPaperLoaderRegistersPaperListener() {
        AtomicReference<Listener> registered = new AtomicReference<>();

        ChatListenerRegistrar.register(manager(registered), plugin(), getClass().getClassLoader());

        assertInstanceOf(PaperChatListener.class, registered.get());
    }

    @Test
    void incompleteModernPaperInstallationFailsLoudlyInsteadOfSilentlyLosingMuteEnforcement() {
        AtomicReference<Listener> registered = new AtomicReference<>();
        ClassLoader missingListener = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("me.leoko.advancedban.bukkit.listener.PaperChatListener".equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        assertThrows(IllegalStateException.class,
                () -> ChatListenerRegistrar.register(manager(registered), plugin(), missingListener));
    }

    private static PluginManager manager(AtomicReference<Listener> registered) {
        return (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class}, (proxy, method, args) -> {
                    if ("registerEvents".equals(method.getName())) {
                        registered.set((Listener) args[0]);
                    }
                    return null;
                });
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> null);
    }
}
