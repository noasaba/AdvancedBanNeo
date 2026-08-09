package me.leoko.advancedban.velocity.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VelocityNetworkSettingsSecurityTest {
    @TempDir
    Path dataDirectory;

    @Test
    void rejectsCredentialDirectoryTraversalAndInvalidNodeIdentifiers() throws Exception {
        writeConfig("../escaped", "survival");
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));

        writeConfig("nodes", "../survival");
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    @Test
    void rejectsMalformedAndShortCredentials() throws Exception {
        writeConfig("nodes", "survival");
        Files.createDirectories(dataDirectory.resolve("nodes"));
        Path credential = dataDirectory.resolve("nodes/survival.key");
        Files.write(credential, "not base64%%%".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));

        Files.write(credential, Base64.getEncoder().encode(new byte[31]));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    @Test
    void credentialAccessorsCannotMutateStoredAuthoritySecrets() throws Exception {
        writeConfig("nodes", "survival");
        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);
        byte[] original = settings.getCredentials().get("survival");
        byte[] expected = original.clone();

        original[0] ^= 1;
        settings.getCredentials().clear();

        assertArrayEquals(expected, settings.getCredentials().get("survival"));
        assertFalse(Arrays.equals(original, settings.getCredentials().get("survival")));
    }

    @Test
    void rejectsCredentialDirectorySymlinkThatEscapesPluginDataDirectory() throws Exception {
        Path pluginData = dataDirectory.resolve("plugin");
        Path outside = dataDirectory.resolve("outside");
        Files.createDirectories(pluginData);
        Files.createDirectories(outside);
        createSymlinkOrSkip(pluginData.resolve("nodes"), outside);
        writeConfig(pluginData, "nodes", "survival");

        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(pluginData));
        assertFalse(Files.exists(outside.resolve("survival.key")),
                "rejected settings must not create a secret outside the plugin directory");
    }

    @Test
    void rejectsCredentialFileSymlinkThatEscapesCredentialDirectory() throws Exception {
        Path pluginData = dataDirectory.resolve("plugin-key");
        Path credentials = pluginData.resolve("nodes");
        Path outside = dataDirectory.resolve("outside.key");
        Files.createDirectories(credentials);
        Files.write(outside, Base64.getEncoder().encode(new byte[32]));
        createSymlinkOrSkip(credentials.resolve("survival.key"), outside);
        writeConfig(pluginData, "nodes", "survival");

        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(pluginData));
    }

    private void writeConfig(String credentialDirectory, String node) throws Exception {
        writeConfig(dataDirectory, credentialDirectory, node);
    }

    private void writeConfig(Path pluginData, String credentialDirectory, String node) throws Exception {
        Files.write(pluginData.resolve("network.yml"), (
                "Enabled: true\n"
                        + "Authority:\n  Id: velocity\n"
                        + "Listen:\n  Host: 127.0.0.1\n  Port: 27785\n"
                        + "Security:\n  CredentialsDirectory: " + credentialDirectory + "\n"
                        + "AllowedNodes:\n  - " + node + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
