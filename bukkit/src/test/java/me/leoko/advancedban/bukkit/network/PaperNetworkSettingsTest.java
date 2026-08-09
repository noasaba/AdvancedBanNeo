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
    void missingSharedKeyRemainsStandaloneWithoutGeneratingNetworkYaml() {
        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertFalse(settings.isAgent());
        assertFalse(Files.exists(dataDirectory.resolve("network.yml")));
    }

    @Test
    void sharedKeyAutomaticallySelectsAgentWithZeroConfigurationDefaults() throws Exception {
        writeKey(new byte[32]);

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertTrue(settings.isAgent());
        assertTrue(settings.isValidAgent());
        assertEquals("127.0.0.1", settings.getHost());
        assertEquals(27785, settings.getPort());
        assertFalse(Files.exists(dataDirectory.resolve("network.yml")));
    }

    @Test
    void generatedInternalAgentIdentityIsStableAndRequiresNoUserConfiguration() throws Exception {
        writeKey(new byte[32]);

        String first = PaperNetworkSettings.load(dataDirectory.toFile()).getNodeId();
        String second = PaperNetworkSettings.load(dataDirectory.toFile()).getNodeId();

        assertEquals(first, second);
        assertEquals(first, java.util.UUID.fromString(first).toString());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("agent.id")));
    }

    @Test
    void existingConfigYamlCanOptionallyOverrideCoordinatorAddress() throws Exception {
        writeKey(new byte[32]);
        Files.write(dataDirectory.resolve("config.yml"), (
                "Network:\n"
                        + "  CoordinatorHost: 10.0.0.10\n"
                        + "  CoordinatorPort: 28785\n")
                .getBytes(StandardCharsets.UTF_8));

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        assertTrue(settings.isValidAgent());
        assertEquals("10.0.0.10", settings.getHost());
        assertEquals(28785, settings.getPort());
    }

    @Test
    void obsoleteNetworkYamlDoesNotControlTheAutomaticallySelectedRole() throws Exception {
        Files.write(dataDirectory.resolve("network.yml"),
                "Mode: AGENT\nCoordinator:\n  Host: attacker.invalid\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(PaperNetworkSettings.load(dataDirectory.toFile()).isAgent());

        writeKey(new byte[32]);
        Files.write(dataDirectory.resolve("network.yml"),
                "Mode: STANDALONE\n".getBytes(StandardCharsets.UTF_8));
        PaperNetworkSettings paired = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(paired.isAgent());
        assertTrue(paired.isValidAgent());
        assertEquals("127.0.0.1", paired.getHost());
    }

    @Test
    void persistedPairingNeverFallsBackToStandaloneAfterSharedKeyLoss() throws Exception {
        writeKey(new byte[32]);
        assertTrue(PaperNetworkSettings.load(dataDirectory.toFile()).isValidAgent());

        Files.delete(dataDirectory.resolve("network.key"));
        PaperNetworkSettings missing = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(missing.isAgent());
        assertFalse(missing.isValidAgent());
    }

    private void writeKey(byte[] credential) throws Exception {
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(credential));
    }
}
