package me.leoko.advancedban.velocity.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityNetworkSettingsTest {
    @TempDir
    Path dataDirectory;

    @Test
    void createsDisabledBackwardCompatibleDefault() throws Exception {
        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);
        assertFalse(settings.isEnabled());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("network.yml")));
        assertTrue(settings.getCredentials().isEmpty());
    }

    @Test
    void createsDistinctPerNodeCredentialsWithoutLoggingOrEmbeddingThem() throws Exception {
        Files.write(dataDirectory.resolve("network.yml"), (
                "Enabled: true\n"
                        + "Authority:\n  Id: velocity\n"
                        + "Listen:\n  Host: 127.0.0.1\n  Port: 27785\n"
                        + "Security:\n  CredentialsDirectory: nodes\n"
                        + "AllowedNodes:\n  - survival\n  - lobby\n")
                .getBytes(StandardCharsets.UTF_8));

        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);
        assertTrue(settings.isEnabled());
        assertEquals(2, settings.getCredentials().size());
        assertFalse(java.util.Arrays.equals(settings.getCredentials().get("survival"),
                settings.getCredentials().get("lobby")));
        assertTrue(Files.isRegularFile(dataDirectory.resolve("nodes/survival.key")));
        assertTrue(Files.isRegularFile(dataDirectory.resolve("nodes/lobby.key")));
    }
}
