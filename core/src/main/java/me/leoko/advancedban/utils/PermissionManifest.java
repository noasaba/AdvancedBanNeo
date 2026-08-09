package me.leoko.advancedban.utils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Complete AdvancedBan 2.3.0-compatible permission surface.
 *
 * <p>Proxy permission managers cannot discover permissions from a Bukkit
 * descriptor. Keeping the generated surface in one place lets adapters expose
 * every node before the first command or exemption check occurs.</p>
 */
public final class PermissionManifest {
    private static final Set<String> PERMISSIONS = buildPermissions();

    private PermissionManifest() {
    }

    /**
     * Gets every permission which AdvancedBan can check at runtime.
     *
     * @return an immutable, deterministic permission set
     */
    public static Set<String> getPermissions() {
        return PERMISSIONS;
    }

    private static Set<String> buildPermissions() {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        permissions.add("ab.*");
        permissions.add("ab.all");

        for (Command command : Command.values()) {
            addWithAllAncestors(permissions, command.getPermission());
        }

        addWithAllAncestors(permissions, "ab.warns.own");
        addWithAllAncestors(permissions, "ab.warns.other");
        addWithAllAncestors(permissions, "ab.notes.own");
        addWithAllAncestors(permissions, "ab.notes.other");
        addWithAllAncestors(permissions, "ab.check.ip");
        addWithAllAncestors(permissions, "ab.reload");
        addWithAllAncestors(permissions, "ab.help");

        for (PunishmentType type : PunishmentType.values()) {
            addWithAllAncestors(permissions, "ab.notify." + type.getName());
            addWithAllAncestors(permissions, "ab.undoNotify." + type.getBasic().getName());

            String exemption = "ab." + type.getName() + ".exempt";
            permissions.add(exemption);
            for (int level = 1; level <= 10; level++) {
                permissions.add(exemption + "." + level);
            }

            if (type.isTemp()) {
                String duration = "ab." + type.getName() + ".dur";
                addWithAllAncestors(permissions, duration + ".max");
                for (int level = 1; level <= 10; level++) {
                    addWithAllAncestors(permissions, duration + "." + level);
                }
            }
        }

        return Collections.unmodifiableSet(permissions);
    }

    private static void addWithAllAncestors(Set<String> permissions, String permission) {
        if (permission == null) {
            return;
        }
        permissions.add(permission);

        String parent = permission;
        while (parent.contains(".")) {
            parent = parent.substring(0, parent.lastIndexOf('.'));
            if (!"ab".equalsIgnoreCase(parent)) {
                permissions.add(parent + ".all");
            }
        }
        permissions.add("ab.all");
    }
}
