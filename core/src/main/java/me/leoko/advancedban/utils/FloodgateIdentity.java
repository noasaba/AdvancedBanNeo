package me.leoko.advancedban.utils;

import java.lang.reflect.Method;
import java.util.UUID;

/** Optional reflective bridge to Floodgate, keeping Floodgate out of the plugin's hard dependencies. */
public final class FloodgateIdentity {
    private static final String API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";

    private FloodgateIdentity() {
    }

    public static boolean isFloodgatePlayer(ClassLoader floodgateClassLoader, UUID uuid) {
        if (floodgateClassLoader == null || uuid == null) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName(API_CLASS, true, floodgateClassLoader);
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            if (api == null) {
                return false;
            }
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }
}
