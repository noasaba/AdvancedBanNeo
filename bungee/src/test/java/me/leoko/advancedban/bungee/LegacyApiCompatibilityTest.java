package me.leoko.advancedban.bungee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LegacyApiCompatibilityTest {

    @Test
    void keepsAdvancedBan230OfflinePermissionTypes() throws Exception {
        Class<?> provider = Class.forName("me.leoko.advancedban.bungee.utils.OfflinePermissionProvider");
        assertNotNull(provider.getMethod("requestOfflinePermissionPlayer", String.class));
        assertNotNull(provider.getMethod("releaseOfflinePermissionPlayer", String.class));
        assertNotNull(provider.getMethod("hasOfflinePerms", String.class, String.class));

        Class<?> luckPermsProvider = Class.forName("me.leoko.advancedban.bungee.utils.LuckPermsPermissionProvider");
        assertNotNull(luckPermsProvider.getConstructor());
        assertNotNull(luckPermsProvider.getMethod("hasOfflinePerms", String.class, String.class));
    }
}
