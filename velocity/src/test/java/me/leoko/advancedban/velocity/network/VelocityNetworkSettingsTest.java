package me.leoko.advancedban.velocity.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityNetworkSettingsTest {
    @TempDir
    Path dataDirectory;

    @Test
    void firstLoadCreatesOneSharedKeyAndUsesAlwaysOnDefaultsWithoutNetworkYaml() throws Exception {
        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);

        assertTrue(settings.isEnabled());
        assertEquals("velocity", settings.getAuthorityId());
        assertEquals("127.0.0.1", settings.getHost());
        assertEquals(27785, settings.getPort());
        assertTrue(settings.wasCredentialGenerated());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("network.key")));
        assertFalse(Files.exists(dataDirectory.resolve("network.yml")));
    }

    @Test
    void reloadReusesExactlyTheSameSharedCredential() throws Exception {
        VelocityNetworkSettings first = VelocityNetworkSettings.load(dataDirectory);
        VelocityNetworkSettings second = VelocityNetworkSettings.load(dataDirectory);

        assertArrayEquals(first.getCredential(), second.getCredential());
        assertFalse(second.wasCredentialGenerated());
    }

    @Test
    void existingConfigYamlCanOptionallyOverrideBindAddress() throws Exception {
        Files.write(dataDirectory.resolve("config.yml"), (
                "Network:\n"
                        + "  BindHost: 0.0.0.0\n"
                        + "  Port: 28785\n")
                .getBytes(StandardCharsets.UTF_8));

        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);

        assertEquals("0.0.0.0", settings.getHost());
        assertEquals(28785, settings.getPort());
    }

    @Test
    void obsoleteNetworkYamlCannotDisableOrReconfigureAuthorityTransport() throws Exception {
        Files.write(dataDirectory.resolve("network.yml"), (
                "Enabled: false\nListen:\n  Host: attacker.invalid\n  Port: 1\n")
                .getBytes(StandardCharsets.UTF_8));

        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);

        assertTrue(settings.isEnabled());
        assertEquals("127.0.0.1", settings.getHost());
        assertEquals(27785, settings.getPort());
    }
}
