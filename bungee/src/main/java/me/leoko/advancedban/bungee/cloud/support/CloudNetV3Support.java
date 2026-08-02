package me.leoko.advancedban.bungee.cloud.support;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bungee.cloud.CloudSupport;

import java.util.UUID;

public class CloudNetV3Support implements CloudSupport {

    private final Class<?> cloudNetDriverClass;
    private final Class<?> playerManagerClass;

    public CloudNetV3Support() {
        try {
            cloudNetDriverClass = Class.forName("de.dytanic.cloudnet.driver.CloudNetDriver");
            playerManagerClass = Class.forName("de.dytanic.cloudnet.ext.bridge.player.IPlayerManager");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("CloudNet v3 API is unavailable", exception);
        }
    }

    @Override
    public void kick(UUID uniqueID, String reason) {
        try {
            Object driver = cloudNetDriverClass.getMethod("getInstance").invoke(null);
            Object servicesRegistry = cloudNetDriverClass.getMethod("getServicesRegistry").invoke(driver);
            Object playerManager = servicesRegistry.getClass()
                    .getMethod("getFirstService", Class.class)
                    .invoke(servicesRegistry, playerManagerClass);
            Object playerExecutor = playerManagerClass.getMethod("getPlayerExecutor", UUID.class)
                    .invoke(playerManager, uniqueID);
            playerExecutor.getClass().getMethod("kick", String.class).invoke(playerExecutor, reason);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Universal.get().log("§cFailed to kick a player through CloudNet v3");
            Universal.get().debugException(exception);
        }
    }
}
