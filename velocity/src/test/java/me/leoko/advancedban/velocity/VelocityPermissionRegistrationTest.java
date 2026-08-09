package me.leoko.advancedban.velocity;

import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import me.leoko.advancedban.utils.PermissionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityPermissionRegistrationTest {
    @TempDir
    Path dataDirectory;

    @Test
    void exposesCompleteManifestThroughVelocityPermissionChecks() throws Exception {
        Set<String> checked = new LinkedHashSet<>();
        ConsoleCommandSource console = (ConsoleCommandSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ConsoleCommandSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        checked.add((String) args[0]);
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
        ProxyServer proxy = (ProxyServer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ProxyServer.class},
                (instance, method, args) -> method.getName().equals("getConsoleCommandSource")
                        ? console : defaultValue(method.getReturnType()));

        VelocityMethods methods = new VelocityMethods(null, proxy, dataDirectory);
        Field available = VelocityMethods.class.getDeclaredField("luckPermsAvailable");
        available.setAccessible(true);
        available.setBoolean(methods, true);

        methods.registerPermissions(PermissionManifest.getPermissions());

        assertEquals(PermissionManifest.getPermissions(), checked);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
