package me.leoko.advancedban.bungee.utils;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy AdvancedBan 2.3.0 LuckPerms provider retained for binary compatibility.
 *
 * @deprecated use {@link LuckPermsOfflineUser}
 */
@Deprecated
public class LuckPermsPermissionProvider implements OfflinePermissionProvider {
    private final LuckPerms luckPerms;
    private final Map<String, User> cachedOfflinePerms = new ConcurrentHashMap<>();

    public LuckPermsPermissionProvider() {
        this.luckPerms = LuckPermsProvider.get();
    }

    @Override
    public void requestOfflinePermissionPlayer(String name) {
        final UserManager userManager = luckPerms.getUserManager();
        final UUID uuid = userManager.lookupUniqueId(name).join();
        if (uuid != null) {
            final User user = userManager.loadUser(uuid).join();
            if (user != null) {
                cachedOfflinePerms.put(name, user);
            }
        }
    }

    @Override
    public void releaseOfflinePermissionPlayer(String name) {
        cachedOfflinePerms.remove(name);
    }

    @Override
    public boolean hasOfflinePerms(String name, String perms) {
        final User user = cachedOfflinePerms.get(name);
        return user != null && user.getCachedData().getPermissionData().checkPermission(perms).asBoolean();
    }
}
