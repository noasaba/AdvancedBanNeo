package me.leoko.advancedban.bukkit.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PaperNetworkSettingsSecurityTest {
    @TempDir
    Path dataDirectory;

    @BeforeEach
    void enableRootNetwork() throws Exception {
        enable(dataDirectory);
    }

    @Test
    void malformedAndShortSharedKeysRemainDegradedAgents() throws Exception {
        Files.write(dataDirectory.resolve("network.key"), "not base64%%%".getBytes(StandardCharsets.US_ASCII));
        PaperNetworkSettings malformed = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(malformed.isAgent());
        assertFalse(malformed.isValidAgent());

        Files.delete(dataDirectory.resolve("network.key"));
        PaperNetworkSettings missingAfterMalformedPairing = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(missingAfterMalformedPairing.isAgent());
        assertFalse(missingAfterMalformedPairing.isValidAgent());

        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[31]));
        PaperNetworkSettings shortCredential = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(shortCredential.isAgent());
        assertFalse(shortCredential.isValidAgent());
    }

    @Test
    void invalidOptionalHostAndPortNeverFallBackToStandaloneAuthority() throws Exception {
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[32]));
        Files.write(dataDirectory.resolve("config.yml"), (
                "Network:\n  CoordinatorHost: ''\n  CoordinatorPort: 70000\n")
                .getBytes(StandardCharsets.UTF_8));

        PaperNetworkSettings invalid = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(invalid.isAgent());
        assertFalse(invalid.isValidAgent());
    }

    @Test
    void nonNumericOptionalPortFailsClosed() throws Exception {
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[32]));
        Files.write(dataDirectory.resolve("config.yml"),
                "Network:\n  CoordinatorPort: not-a-port\n".getBytes(StandardCharsets.UTF_8));

        PaperNetworkSettings invalid = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(invalid.isAgent());
        assertFalse(invalid.isValidAgent());
    }

    @Test
    void sharedKeySymlinkOutsidePluginDirectoryIsRejected() throws Exception {
        Path pluginData = dataDirectory.resolve("plugin");
        Path outside = dataDirectory.resolve("outside.key");
        Files.createDirectories(pluginData);
        enable(pluginData);
        Files.write(outside, Base64.getEncoder().encode(new byte[32]));
        createSymlinkOrSkip(pluginData.resolve("network.key"), outside);

        PaperNetworkSettings settings = PaperNetworkSettings.load(pluginData.toFile());

        assertTrue(settings.isAgent());
        assertFalse(settings.isValidAgent());
    }

    @Test
    void malformedKeyPathAndMarkerPathCannotTriggerStandaloneSplitBrain() throws Exception {
        Path keyDirectory = dataDirectory.resolve("key-directory");
        Files.createDirectories(keyDirectory.resolve("network.key"));
        enable(keyDirectory);
        PaperNetworkSettings malformedKey = PaperNetworkSettings.load(keyDirectory.toFile());
        assertTrue(malformedKey.isAgent());
        assertFalse(malformedKey.isValidAgent());

        Path danglingKey = dataDirectory.resolve("dangling-key");
        Files.createDirectories(danglingKey);
        enable(danglingKey);
        createSymlinkOrSkip(danglingKey.resolve("network.key"), danglingKey.resolve("missing-target"));
        PaperNetworkSettings dangling = PaperNetworkSettings.load(danglingKey.toFile());
        assertTrue(dangling.isAgent());
        assertFalse(dangling.isValidAgent());

        Path markerDirectory = dataDirectory.resolve("marker-directory");
        Files.createDirectories(markerDirectory.resolve(".agent-paired"));
        enable(markerDirectory);
        PaperNetworkSettings markerOnly = PaperNetworkSettings.load(markerDirectory.toFile());
        assertTrue(markerOnly.isAgent());
        assertFalse(markerOnly.isValidAgent());
    }

    @Test
    void symbolicAgentIdentityFailsClosedInsteadOfFollowingExternalState() throws Exception {
        Path pluginData = dataDirectory.resolve("identity-plugin");
        Path outside = dataDirectory.resolve("outside.id");
        Files.createDirectories(pluginData);
        enable(pluginData);
        Files.write(pluginData.resolve("network.key"), Base64.getEncoder().encode(new byte[32]));
        Files.write(outside, "00000000-0000-0000-0000-000000000001\n"
                .getBytes(StandardCharsets.US_ASCII));
        createSymlinkOrSkip(pluginData.resolve("agent.id"), outside);

        PaperNetworkSettings settings = PaperNetworkSettings.load(pluginData.toFile());

        assertTrue(settings.isAgent());
        assertFalse(settings.isValidAgent());
    }

    @Test
    void zeroOrMalformedInternalIdentityFailsBeforeOpeningAProtocolSession() throws Exception {
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[32]));
        Files.write(dataDirectory.resolve("agent.id"),
                "00000000-0000-0000-0000-000000000000\n".getBytes(StandardCharsets.US_ASCII));
        PaperNetworkSettings zero = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(zero.isAgent());
        assertFalse(zero.isValidAgent());

        Files.write(dataDirectory.resolve("agent.id"), "not-a-uuid\n".getBytes(StandardCharsets.US_ASCII));
        PaperNetworkSettings malformed = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(malformed.isAgent());
        assertFalse(malformed.isValidAgent());
    }

    @Test
    void returnedCredentialIsDefensivelyCopied() throws Exception {
        byte[] credential = new byte[32];
        Arrays.fill(credential, (byte) 8);
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(credential));
        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        byte[] returned = settings.getCredential();
        returned[0] ^= 1;

        assertArrayEquals(credential, settings.getCredential());
    }

    @Test
    void copiedSharedCredentialIsRestrictedToOwnerOnPosixFilesystems() throws Exception {
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[32]));
        assertTrue(PaperNetworkSettings.load(dataDirectory.toFile()).isValidAgent());
        try {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(dataDirectory.resolve("network.key")));
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "POSIX permissions are unavailable");
        }
    }

    @Test
    void rejectsCidrHostAndEscapingKeyFile() throws Exception {
        Files.write(dataDirectory.resolve("config.yml"), ("Network:\n"
                + "  Enabled: true\n"
                + "  CoordinatorHost: 10.6.0.1/24\n"
                + "  KeyFile: ../network.key\n")
                .getBytes(StandardCharsets.UTF_8));
        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(settings.isAgent());
        assertFalse(settings.isValidAgent());
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
