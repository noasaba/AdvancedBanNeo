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
    void newConfigurationIsDisabledCommentedAndDoesNotGenerateKey() throws Exception {
        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);
        String generated = configText();

        assertFalse(settings.isEnabled());
        assertFalse(Files.exists(dataDirectory.resolve("network.key")));
        assertTrue(generated.contains("  Enabled: false"));
        assertTrue(generated.contains("  BindHost: 127.0.0.1"));
        assertTrue(generated.contains("  Port: 27785"));
        assertTrue(generated.contains("  KeyFile: network.key"));
        assertTrue(generated.contains("# Paper Agent transport."));
    }

    @Test
    void explicitlyEnabledTransportGeneratesAndReusesSharedKey() throws Exception {
        writeConfig("Network:\n  Enabled: true\n");

        VelocityNetworkSettings first = VelocityNetworkSettings.load(dataDirectory);
        VelocityNetworkSettings second = VelocityNetworkSettings.load(dataDirectory);

        assertTrue(first.isEnabled());
        assertTrue(first.wasCredentialGenerated());
        assertFalse(second.wasCredentialGenerated());
        assertArrayEquals(first.getCredential(), second.getCredential());
    }

    @Test
    void explicitlyDisabledTransportDoesNotReadOrReplaceExistingKey() throws Exception {
        writeConfig("Network:\n  Enabled: false\n");
        byte[] original = "leave-this-file-alone".getBytes(StandardCharsets.US_ASCII);
        Files.write(dataDirectory.resolve("network.key"), original);

        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);

        assertFalse(settings.isEnabled());
        assertEquals(null, settings.getCredential());
        assertArrayEquals(original, Files.readAllBytes(dataDirectory.resolve("network.key")));
    }

    @Test
    void legacyExistingVelocityConfigMigratesToEnabledOnce() throws Exception {
        writeConfig("Debug: false\n");

        VelocityNetworkSettings first = VelocityNetworkSettings.load(dataDirectory);
        String migrated = configText();
        VelocityNetworkSettings second = VelocityNetworkSettings.load(dataDirectory);

        assertTrue(first.isEnabled());
        assertTrue(second.isEnabled());
        assertTrue(migrated.contains("  Enabled: true"));
        assertEquals(migrated, configText());
    }

    @Test
    void customRelativeKeyAndEndpointAreHonored() throws Exception {
        writeConfig("Network:\n"
                + "  Enabled: true\n"
                + "  BindHost: 0.0.0.0\n"
                + "  Port: 28785\n"
                + "  KeyFile: secrets/network.key\n");

        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);

        assertTrue(settings.isEnabled());
        assertEquals("0.0.0.0", settings.getHost());
        assertEquals(28785, settings.getPort());
        assertTrue(Files.isRegularFile(dataDirectory.resolve("secrets/network.key")));
    }

    private void writeConfig(String value) throws Exception {
        Files.write(dataDirectory.resolve("config.yml"), value.getBytes(StandardCharsets.UTF_8));
    }

    private String configText() throws Exception {
        return new String(Files.readAllBytes(dataDirectory.resolve("config.yml")), StandardCharsets.UTF_8);
    }
}
