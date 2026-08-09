package me.leoko.advancedban.bungee.utils;

import me.leoko.advancedban.utils.Permissionable;
import java.lang.reflect.Method;
import java.util.List;

public class CloudNetCloudPermsOfflineUser implements Permissionable {
    private Object permissionUser;

    public CloudNetCloudPermsOfflineUser(String name) {
        try {
            Class<?> driverClass = Class.forName("de.dytanic.cloudnet.driver.CloudNetDriver");
            Object driver = driverClass.getMethod("getInstance").invoke(null);
            Object permissionManagement = driverClass.getMethod("getPermissionManagement").invoke(driver);
            Object result = permissionManagement.getClass().getMethod("getUsers", String.class)
                    .invoke(permissionManagement, name);
            List<?> users = (List<?>) result;
            if (!users.isEmpty()) {
                permissionUser = users.get(0);
            }
        } catch (ReflectiveOperationException | ClassCastException | LinkageError ignored) {
            permissionUser = null;
        }
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permissionUser == null) {
            return false;
        }
        try {
            Method hasPermission = permissionUser.getClass().getMethod("hasPermission", String.class);
            Object permissionResult = hasPermission.invoke(permissionUser, permission);
            return (boolean) permissionResult.getClass().getMethod("asBoolean").invoke(permissionResult);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return false;
        }
    }
}
