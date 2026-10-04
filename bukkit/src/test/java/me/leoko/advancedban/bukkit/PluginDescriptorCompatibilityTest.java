package me.leoko.advancedban.bukkit;

import me.leoko.advancedban.utils.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorCompatibilityTest {

    @Test
    void allAdvancedBan230CommandsAndAliasesRemainDeclared() {
        InputStream resource = getClass().getResourceAsStream("/plugin.yml");
        assertNotNull(resource, "plugin.yml must be present in the Bukkit artifact");

        YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        ConfigurationSection commands = descriptor.getConfigurationSection("commands");
        assertNotNull(commands);

        assertEquals(new HashSet<>(Arrays.asList(
                "AdvancedBan", "ban", "tempban", "ipban", "tempipban", "kick", "warn", "note",
                "tempwarn", "mute", "tempmute", "banlist", "history", "warns", "notes", "check",
                "systemPrefs", "unwarn", "unnote", "unpunish", "unban", "unmute", "change-reason"
        )), commands.getKeys(false));

        assertEquals(Arrays.asList("banip", "ban-ip"), commands.getStringList("ipban.aliases"));
        assertEquals(Arrays.asList("tipban"), commands.getStringList("tempipban.aliases"));
        assertTrue(descriptor.getStringList("softdepend").contains("ChatSyncerChat"));
        assertTrue(descriptor.getStringList("softdepend").contains("floodgate"));

        Set<String> descriptorNames = new HashSet<>();
        for (String primary : commands.getKeys(false)) {
            descriptorNames.add(primary.toLowerCase());
            for (String alias : commands.getStringList(primary + ".aliases")) {
                descriptorNames.add(alias.toLowerCase());
            }
        }

        Set<String> runtimeNames = new HashSet<>();
        for (Command command : Command.values()) {
            runtimeNames.addAll(Arrays.asList(command.getNames()));
        }
        assertEquals(descriptorNames, runtimeNames,
                "Bukkit descriptor and runtime/Bungee registration surface must stay aligned");
    }
}
