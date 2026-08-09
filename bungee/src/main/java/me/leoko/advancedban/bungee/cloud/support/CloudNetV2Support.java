
package me.leoko.advancedban.bungee.cloud.support;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bungee.cloud.CloudSupport;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

public class CloudNetV2Support implements CloudSupport {

    private final Class<?> cloudServerClass;
    private final Class<?> playerExecutorBridgeClass;

    public CloudNetV2Support() {
        try {
            cloudServerClass = Class.forName("de.dytanic.cloudnet.bridge.CloudServer");
            playerExecutorBridgeClass = Class.forName("de.dytanic.cloudnet.api.player.PlayerExecutorBridge");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("CloudNet v2 API is unavailable", exception);
        }
    }

    @Override
    public void kick(UUID uniqueID, String reason) {
        try {
            Object cloudServer = cloudServerClass.getMethod("getInstance").invoke(null);
            Object cloudPlayers = cloudServerClass.getMethod("getCloudPlayers").invoke(cloudServer);
            Object cloudPlayer = ((Map<?, ?>) cloudPlayers).get(uniqueID);
            Object playerExecutor = playerExecutorBridgeClass.getField("INSTANCE").get(null);

            Method kickPlayer = null;
            for (Method method : playerExecutorBridgeClass.getMethods()) {
                if (method.getName().equals("kickPlayer") && method.getParameterCount() == 2) {
                    kickPlayer = method;
                    break;
                }
            }
            if (kickPlayer == null) {
                throw new NoSuchMethodException("PlayerExecutorBridge#kickPlayer");
            }
            kickPlayer.invoke(playerExecutor, cloudPlayer, reason);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Universal.get().log("§cFailed to kick a player through CloudNet v2");
            Universal.get().debugException(exception);
        }
    }
}
