package me.leoko.advancedban;

import me.leoko.advancedban.utils.PermissionManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exhaustive runtime matrix for every registered AdvancedBan permission node. */
class PermissionMatrixTest {

    @TempDir
    File dataFolder;

    private Field methodsField;
    private MethodInterface previous;

    @BeforeEach
    void captureMethods() throws Exception {
        methodsField = Universal.class.getDeclaredField("mi");
        methodsField.setAccessible(true);
        previous = (MethodInterface) methodsField.get(Universal.get());
    }

    @AfterEach
    void restoreMethods() throws Exception {
        methodsField.set(Universal.get(), previous);
    }

    @Test
    void everyPermissionSupportsExactGrantDefaultDenyAndAbWildcard() throws Exception {
        for (String permission : PermissionManifest.getPermissions()) {
            if (permission.endsWith(".*") || permission.endsWith(".all")) {
                continue;
            }
            install(false, Collections.<String>emptySet(), Collections.<String>emptySet());
            assertFalse(Universal.get().hasPerms("sender", permission), permission + " must default deny");

            install(false, Collections.singleton(permission), Collections.<String>emptySet());
            assertTrue(Universal.get().hasPerms("sender", permission), permission + " exact grant");

            install(false, Collections.singleton("ab.*"), Collections.<String>emptySet());
            assertTrue(Universal.get().hasPerms("sender", permission), permission + " via ab.*");

            install(false, Collections.singleton("ab.*"), Collections.singleton(permission));
            assertFalse(Universal.get().hasPerms("sender", permission), permission + " explicit deny");
        }
    }

    @Test
    void everyPermissionKeepsAdvancedBan230AllAncestorBehavior() throws Exception {
        for (String permission : PermissionManifest.getPermissions()) {
            if (permission.endsWith(".*") || permission.endsWith(".all") || !permission.contains(".")) {
                continue;
            }
            String parent = permission.substring(0, permission.lastIndexOf('.')) + ".all";

            install(false, Collections.singleton(parent), Collections.<String>emptySet());
            assertFalse(Universal.get().hasPerms("sender", permission),
                    "EnableAllPermissionNodes=false must ignore " + parent);

            install(true, Collections.singleton(parent), Collections.<String>emptySet());
            assertTrue(Universal.get().hasPerms("sender", permission),
                    permission + " must inherit " + parent);

            install(true, Collections.singleton("ab.all"), Collections.<String>emptySet());
            assertTrue(Universal.get().hasPerms("sender", permission),
                    permission + " must inherit ab.all");
        }
    }

    private void install(boolean enableAll, Set<String> granted, Set<String> denied) throws Exception {
        methodsField.set(Universal.get(), new MatrixMethods(dataFolder, enableAll, granted, denied));
    }

    private static final class MatrixMethods extends TestMethods {
        private final boolean enableAll;
        private final Set<String> granted;
        private final Set<String> denied;

        private MatrixMethods(File dataFolder, boolean enableAll,
                              Set<String> granted, Set<String> denied) {
            super(dataFolder);
            this.enableAll = enableAll;
            this.granted = new HashSet<>(granted);
            this.denied = new HashSet<>(denied);
        }

        @Override
        public boolean hasPerms(Object player, String permission) {
            if (denied.contains(permission)) {
                return false;
            }
            if (granted.contains(permission)) {
                return true;
            }
            for (String node : granted) {
                if (node.endsWith(".*")
                        && permission.startsWith(node.substring(0, node.length() - 1))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean getBoolean(Object file, String path, boolean fallback) {
            return "EnableAllPermissionNodes".equals(path)
                    ? enableAll : super.getBoolean(file, path, fallback);
        }
    }
}
