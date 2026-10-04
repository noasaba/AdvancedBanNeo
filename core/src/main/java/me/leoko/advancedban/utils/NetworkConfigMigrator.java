package me.leoko.advancedban.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds missing Network keys without overwriting existing configuration values or comments. */
public final class NetworkConfigMigrator {
    private NetworkConfigMigrator() {
    }

    public static void ensure(Path configFile, LinkedHashMap<String, String> defaults,
                              List<String> newBlockComments) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8));
        int networkStart = findRootSection(lines, "Network");
        boolean changed = false;
        if (networkStart < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                lines.add("");
            }
            for (String comment : newBlockComments) {
                lines.add("# " + comment);
            }
            lines.add("Network:");
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                lines.add("  " + entry.getKey() + ": " + entry.getValue());
            }
            changed = true;
        } else {
            int sectionEnd = findSectionEnd(lines, networkStart + 1);
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (int i = networkStart + 1; i < sectionEnd; i++) {
                String line = lines.get(i);
                if (line.startsWith("  ") && !line.startsWith("   ") && !line.trim().startsWith("#")) {
                    int colon = line.indexOf(':', 2);
                    if (colon > 2) {
                        existing.add(line.substring(2, colon).trim());
                    }
                }
            }
            List<String> additions = new ArrayList<>();
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                if (!existing.contains(entry.getKey())) {
                    additions.add("  " + entry.getKey() + ": " + entry.getValue());
                }
            }
            if (!additions.isEmpty()) {
                lines.addAll(sectionEnd, additions);
                changed = true;
            }
        }
        if (changed) {
            writeAtomically(configFile, lines);
        }
    }

    private static int findRootSection(List<String> lines, String name) {
        String prefix = name + ":";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(prefix)) {
                String suffix = line.substring(prefix.length()).trim();
                if (suffix.isEmpty() || suffix.startsWith("#")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findSectionEnd(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")
                    && !Character.isWhitespace(line.charAt(0))) {
                return i;
            }
        }
        return lines.size();
    }

    private static void writeAtomically(Path configFile, List<String> lines) throws IOException {
        Path temporary = Files.createTempFile(configFile.getParent(), "config", ".migration");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
