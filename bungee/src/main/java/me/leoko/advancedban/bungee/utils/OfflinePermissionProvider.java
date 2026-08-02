package me.leoko.advancedban.bungee.utils;

/**
 * Legacy AdvancedBan 2.3.0 offline-permission integration contract.
 *
 * @deprecated use {@link me.leoko.advancedban.utils.Permissionable} through
 *             {@link me.leoko.advancedban.MethodInterface#getOfflinePermissionPlayer(String)}
 */
@Deprecated
public interface OfflinePermissionProvider {
    void requestOfflinePermissionPlayer(String name);

    void releaseOfflinePermissionPlayer(String name);

    boolean hasOfflinePerms(String name, String perms);
}
