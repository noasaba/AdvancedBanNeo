package me.leoko.advancedban.velocity.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VelocityNetworkSettingsSecurityTest {
    @TempDir
    Path dataDirectory;

    @BeforeEach
    void enableRootNetwork() throws Exception {
        enable(dataDirectory);
    }

    @Test
    void rejectsMalformedAndShortSharedCredentials() throws Exception {
        Path credential = dataDirectory.resolve("network.key");
        Files.write(credential, "not base64%%%".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));

        Files.write(credential, Base64.getEncoder().encode(new byte[31]));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    @Test
    void credentialAccessorCannotMutateStoredAuthoritySecret() throws Exception {
        VelocityNetworkSettings settings = VelocityNetworkSettings.load(dataDirectory);
        byte[] original = settings.getCredential();
        byte[] expected = original.clone();

        original[0] ^= 1;

        assertArrayEquals(expected, settings.getCredential());
        assertFalse(Arrays.equals(original, settings.getCredential()));
    }

    @Test
    void rejectsSharedKeySymlinkThatEscapesPluginDataDirectory() throws Exception {
        Path pluginData = dataDirectory.resolve("plugin");
        Path outside = dataDirectory.resolve("outside.key");
        Files.createDirectories(pluginData);
        enable(pluginData);
        Files.write(outside, Base64.getEncoder().encode(new byte[32]));
        createSymlinkOrSkip(pluginData.resolve("network.key"), outside);

        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(pluginData));
    }

    @Test
    void rejectsDirectoryAtSharedKeyPath() throws Exception {
        Files.createDirectory(dataDirectory.resolve("network.key"));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    @Test
    void rejectsInvalidOptionalBindAddress() throws Exception {
        Files.write(dataDirectory.resolve("config.yml"),
                "Network:\n  BindHost: ''\n  Port: 70000\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    @Test
    void rejectsNonNumericOptionalPortInsteadOfSilentlyUsingDefault() throws Exception {
        Files.write(dataDirectory.resolve("config.yml"),
                "Network:\n  Port: not-a-port\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    @Test
    void generatedSharedCredentialIsOwnerOnlyOnPosixFilesystems() throws Exception {
        VelocityNetworkSettings.load(dataDirectory);
        try {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(dataDirectory.resolve("network.key")));
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "POSIX permissions are unavailable");
        }
    }

    @Test
    void rejectsCidrBindHostAndEscapingKeyFile() throws Exception {
        Files.write(dataDirectory.resolve("config.yml"), ("Network:\n"
                + "  Enabled: true\n"
                + "  BindHost: 10.6.0.1/24\n"
                + "  KeyFile: ../network.key\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> VelocityNetworkSettings.load(dataDirectory));
    }

    private void enable(Path directory) throws Exception {
        Files.createDirectories(directory);
        Files.write(directory.resolve("config.yml"), "Network:\n  Enabled: true\n"
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
