package me.leoko.advancedban.bukkit.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperNetworkSettingsTest {
    @TempDir
    Path dataDirectory;

    @Test
    void missingNetworkConfigurationRemainsStandalone() {
        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());
        assertFalse(settings.isAgent());
    }

    @Test
    void pairedConfigurationRemainsAgentEvenWhenAuthorityIsUnavailable() throws Exception {
        byte[] credential = new byte[32];
        Files.write(dataDirectory.resolve("network.key"),
                Base64.getEncoder().encode(credential));
        Files.write(dataDirectory.resolve("network.yml"), (
                "Mode: AGENT\n"
                        + "Node:\n  Id: survival\n"
                        + "Coordinator:\n  Host: 127.0.0.1\n  Port: 27785\n"
                        + "Security:\n  KeyFile: network.key\n")
                .getBytes(StandardCharsets.UTF_8));

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(settings.isAgent());
        assertTrue(settings.isValidAgent());
    }

    @Test
    void missingCredentialFailsClosedAsAgent() throws Exception {
        Files.write(dataDirectory.resolve("network.yml"), (
                "Mode: AGENT\nNode:\n  Id: survival\n")
                .getBytes(StandardCharsets.UTF_8));

        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(settings.isAgent());
        assertFalse(settings.isValidAgent());
    }

    @Test
    void persistedPairingNeverFallsBackToStandaloneAfterConfigurationLoss() throws Exception {
        Files.write(dataDirectory.resolve("custom.key"), Base64.getEncoder().encode(new byte[32]));
        Files.write(dataDirectory.resolve("network.yml"), (
                "Mode: AGENT\nNode:\n  Id: survival\n"
                        + "Security:\n  KeyFile: custom.key\n")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(PaperNetworkSettings.load(dataDirectory.toFile()).isValidAgent());

        Files.delete(dataDirectory.resolve("network.yml"));
        Files.delete(dataDirectory.resolve("custom.key"));
        PaperNetworkSettings missing = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(missing.isAgent());
        assertFalse(missing.isValidAgent());

        Files.write(dataDirectory.resolve("network.yml"), "Mode: STANDALONE\n"
                .getBytes(StandardCharsets.UTF_8));
        PaperNetworkSettings downgraded = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(downgraded.isAgent());
        assertFalse(downgraded.isValidAgent());
    }
}
