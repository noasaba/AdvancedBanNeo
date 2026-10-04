package me.leoko.advancedban.bukkit.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperNetworkSettingsTest {
    @TempDir
    Path dataDirectory;

    @Test
    void newConfigurationIsExplicitlyDisabledAndCommented() throws Exception {
        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());
        String generated = configText();

        assertFalse(settings.isAgent());
        assertTrue(generated.contains("Network:"));
        assertTrue(generated.contains("  Enabled: false"));
        assertTrue(generated.contains("  CoordinatorHost: 127.0.0.1"));
        assertTrue(generated.contains("  CoordinatorPort: 27785"));
        assertTrue(generated.contains("  KeyFile: network.key"));
        assertTrue(generated.contains("  FailClosed: false"));
        assertTrue(generated.contains("# Velocity Authority connection."));
    }

    @Test
    void disabledConfigurationIgnoresExistingKeyAndRemainsStandalone() throws Exception {
        writeConfig("Network:\n  Enabled: false\n");
        writeKey(dataDirectory.resolve("network.key"), new byte[32]);

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertFalse(settings.isAgent());
        assertFalse(Files.exists(dataDirectory.resolve("agent.id")));
    }

    @Test
    void enabledConfigurationSelectsAgentAndUsesDefaults() throws Exception {
        writeConfig("Network:\n  Enabled: true\n");
        writeKey(dataDirectory.resolve("network.key"), new byte[32]);

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertTrue(settings.isAgent());
        assertTrue(settings.isValidAgent());
        assertFalse(settings.isFailClosed());
        assertEquals("127.0.0.1", settings.getHost());
        assertEquals(27785, settings.getPort());
    }

    @Test
    void enabledAgentUsesRelativeCustomKeyAndConfiguredEndpoint() throws Exception {
        writeConfig("Network:\n"
                + "  Enabled: true\n"
                + "  CoordinatorHost: authority.internal\n"
                + "  CoordinatorPort: 28785\n"
                + "  KeyFile: secrets/network.key\n"
                + "  FailClosed: false\n");
        Files.createDirectories(dataDirectory.resolve("secrets"));
        writeKey(dataDirectory.resolve("secrets/network.key"), new byte[32]);

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertTrue(settings.isValidAgent());
        assertFalse(settings.isFailClosed());
        assertEquals("authority.internal", settings.getHost());
        assertEquals(28785, settings.getPort());
    }

    @Test
    void legacyConfigurationMigratesOnceFromPreviousPairingState() throws Exception {
        writeConfig("Debug: false\n");
        writeKey(dataDirectory.resolve("network.key"), new byte[32]);

        PaperNetworkSettings first = PaperNetworkSettings.load(dataDirectory.toFile());
        String migrated = configText();
        PaperNetworkSettings second = PaperNetworkSettings.load(dataDirectory.toFile());

        assertTrue(first.isValidAgent());
        assertTrue(second.isValidAgent());
        assertTrue(migrated.contains("  Enabled: true"));
        assertEquals(migrated, configText());
    }

    @Test
    void legacyUnpairedConfigurationMigratesToDisabled() throws Exception {
        writeConfig("Debug: false\n");

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertFalse(settings.isAgent());
        assertTrue(configText().contains("  Enabled: false"));
    }

    @Test
    void existingNetworkValuesAndCommentsAreNotOverwritten() throws Exception {
        writeConfig("# keep me\nNetwork:\n"
                + "  Enabled: false\n"
                + "  CoordinatorHost: custom.example\n");
        PaperNetworkSettings.load(dataDirectory.toFile());
        String migrated = configText();

        assertTrue(migrated.contains("# keep me"));
        assertTrue(migrated.contains("  Enabled: false"));
        assertTrue(migrated.contains("  CoordinatorHost: custom.example"));
    }

    @Test
    void generatedInternalAgentIdentityIsStable() throws Exception {
        writeConfig("Network:\n  Enabled: true\n");
        writeKey(dataDirectory.resolve("network.key"), new byte[32]);

        String first = PaperNetworkSettings.load(dataDirectory.toFile()).getNodeId();
        String second = PaperNetworkSettings.load(dataDirectory.toFile()).getNodeId();

        assertEquals(first, second);
        assertEquals(first, java.util.UUID.fromString(first).toString());
    }

    private void writeConfig(String value) throws Exception {
        Files.write(dataDirectory.resolve("config.yml"), value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeKey(Path path, byte[] credential) throws Exception {
        Files.write(path, Base64.getEncoder().encode(credential));
    }

    private String configText() throws Exception {
        return new String(Files.readAllBytes(dataDirectory.resolve("config.yml")), StandardCharsets.UTF_8);
    }
}
