package me.leoko.advancedban;

import me.leoko.advancedban.utils.Command;
import me.leoko.advancedban.utils.PermissionManifest;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.tabcompletion.TabCompleter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCompatibilityTest {

    @TempDir
    File dataFolder;

    private Field methodsField;
    private MethodInterface previousMethods;

    @BeforeEach
    void captureUniversalMethods() throws Exception {
        methodsField = Universal.class.getDeclaredField("mi");
        methodsField.setAccessible(true);
        previousMethods = (MethodInterface) methodsField.get(Universal.get());
    }

    @AfterEach
    void restoreUniversalMethods() throws Exception {
        methodsField.set(Universal.get(), previousMethods);
    }

    @Test
    void exactAndWildcardPermissionBehaviorRemainsCompatible() throws Exception {
        RecordingPermissions exact = install(false, "ab.ban.perma");
        assertTrue(Universal.get().hasPerms("sender", "ab.ban.perma"));
        assertFalse(Universal.get().hasPerms("sender", "ab.ban.temp"));
        assertEquals(Arrays.asList("ab.ban.perma", "ab.ban.temp"), exact.queries);

        install(false, "ab.*");
        assertTrue(Universal.get().hasPerms("sender", "ab.ban.perma"));
        assertTrue(Universal.get().hasPerms("sender", "ab.check.ip"));
        assertTrue(Universal.get().hasPerms("sender", "ab.Tempwarn.dur.10"));
    }

    @Test
    void enableAllPermissionNodesChecksEveryAncestor() throws Exception {
        RecordingPermissions disabled = install(false, "ab.ban.all");
        assertFalse(Universal.get().hasPerms("sender", "ab.ban.perma"));
        assertEquals(Collections.singletonList("ab.ban.perma"), disabled.queries);

        RecordingPermissions category = install(true, "ab.ban.all");
        assertTrue(Universal.get().hasPerms("sender", "ab.ban.perma"));
        assertEquals(Arrays.asList("ab.ban.perma", "ab.ban.all"), category.queries);

        install(true, "ab.all");
        assertTrue(Universal.get().hasPerms("sender", "ab.ban.perma"));
        assertTrue(Universal.get().hasPerms("sender", "ab.check.ip"));
        assertTrue(Universal.get().hasPerms("sender", "ab.all.undo"));
    }

    @Test
    void warnsTabCompletionUsesWarnsOtherPermission() throws Exception {
        install(false, "ab.warns.other");

        List<String> suggestions = Command.WARNS.getTabCompleter()
                .onTabComplete("sender", new String[]{""});

        assertEquals(Arrays.asList("<Name>", "<Page>"), suggestions);

        install(false, "ab.notes.other");
        suggestions = Command.WARNS.getTabCompleter()
                .onTabComplete("sender", new String[]{""});
        assertEquals(Arrays.asList("<Name>", "<Page>"), suggestions,
                "the 2.3.0 typo must remain accepted for existing permission setups");
    }

    @Test
    void advancedBan230MethodInterfaceSignaturesRemainAvailable() throws Exception {
        Method legacyRegistration = MethodInterface.class.getMethod(
                "setCommandExecutor", String.class, TabCompleter.class);
        Method permissionRegistration = MethodInterface.class.getMethod(
                "setCommandExecutor", String.class, String.class, TabCompleter.class);
        Method request = MethodInterface.class.getMethod("requestOfflinePermissionPlayer", String.class);
        Method release = MethodInterface.class.getMethod("releaseOfflinePermissionPlayer", String.class);
        Method hasOffline = MethodInterface.class.getMethod("hasOfflinePerms", String.class, String.class);
        Method manifestRegistration = MethodInterface.class.getMethod("registerPermissions", Collection.class);

        assertTrue(legacyRegistration.isDefault());
        assertTrue(permissionRegistration.isDefault());
        assertTrue(request.isDefault());
        assertTrue(release.isDefault());
        assertTrue(hasOffline.isDefault());
        assertTrue(manifestRegistration.isDefault());
    }

    @Test
    void permissionManifestContainsEveryRuntimeGeneratedNode() {
        Set<String> permissions = PermissionManifest.getPermissions();
        assertTrue(permissions.contains("ab.*"));
        assertTrue(permissions.contains("ab.all"));

        for (Command command : Command.values()) {
            if (command.getPermission() != null) {
                assertTrue(permissions.contains(command.getPermission()), command.getPermission());
            }
        }

        for (String permission : Arrays.asList(
                "ab.warns.own", "ab.warns.other", "ab.notes.own", "ab.notes.other",
                "ab.check.ip", "ab.reload", "ab.help")) {
            assertTrue(permissions.contains(permission), permission);
        }

        for (PunishmentType type : PunishmentType.values()) {
            assertTrue(permissions.contains("ab.notify." + type.getName()));
            assertTrue(permissions.contains("ab.undoNotify." + type.getBasic().getName()));
            for (int level = 1; level <= 10; level++) {
                assertTrue(permissions.contains("ab." + type.getName() + ".exempt." + level));
                if (type.isTemp()) {
                    assertTrue(permissions.contains("ab." + type.getName() + ".dur." + level));
                }
            }
            if (type.isTemp()) {
                assertTrue(permissions.contains("ab." + type.getName() + ".dur.max"));
            }
        }

        assertTrue(permissions.contains("ab.note.all"));
        assertTrue(permissions.contains("ab.Tempban.dur.all"));
    }

    private RecordingPermissions install(boolean enableAll, String... granted) throws Exception {
        RecordingPermissions methods = new RecordingPermissions(dataFolder, enableAll, granted);
        methodsField.set(Universal.get(), methods);
        return methods;
    }

    private static class RecordingPermissions extends TestMethods {
        private final boolean enableAll;
        private final Set<String> granted;
        private final java.util.ArrayList<String> queries = new java.util.ArrayList<>();

        RecordingPermissions(File dataFolder, boolean enableAll, String... granted) {
            super(dataFolder);
            this.enableAll = enableAll;
            this.granted = new HashSet<>(Arrays.asList(granted));
        }

        @Override
        public boolean hasPerms(Object player, String permission) {
            queries.add(permission);
            if (granted.contains(permission)) {
                return true;
            }
            for (String node : granted) {
                if (node.endsWith(".*") && permission.startsWith(node.substring(0, node.length() - 1))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean getBoolean(Object file, String path, boolean def) {
            if ("EnableAllPermissionNodes".equals(path)) {
                return enableAll;
            }
            return super.getBoolean(file, path, def);
        }
    }
}
