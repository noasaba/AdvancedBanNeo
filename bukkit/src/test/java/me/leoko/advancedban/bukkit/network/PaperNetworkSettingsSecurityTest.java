package me.leoko.advancedban.bukkit.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperNetworkSettingsSecurityTest {
    @TempDir
    Path dataDirectory;

    @Test
    void traversalMalformedAndShortCredentialConfigurationsRemainDegradedAgents() throws Exception {
        writeAgentConfig("../outside.key", "survival", 27785);
        PaperNetworkSettings traversal = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(traversal.isAgent());
        assertFalse(traversal.isValidAgent());

        writeAgentConfig("network.key", "survival", 27785);
        Files.write(dataDirectory.resolve("network.key"), "not base64%%%".getBytes(StandardCharsets.US_ASCII));
        PaperNetworkSettings malformed = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(malformed.isAgent());
        assertFalse(malformed.isValidAgent());

        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[31]));
        PaperNetworkSettings shortCredential = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(shortCredential.isAgent());
        assertFalse(shortCredential.isValidAgent());
    }

    @Test
    void invalidNodeAndPortNeverFallBackToStandaloneAuthority() throws Exception {
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(new byte[32]));
        writeAgentConfig("network.key", "../survival", 27785);
        PaperNetworkSettings invalidNode = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(invalidNode.isAgent());
        assertFalse(invalidNode.isValidAgent());

        writeAgentConfig("network.key", "survival", 70000);
        PaperNetworkSettings invalidPort = PaperNetworkSettings.load(dataDirectory.toFile());
        assertTrue(invalidPort.isAgent());
        assertFalse(invalidPort.isValidAgent());
    }

    @Test
    void returnedCredentialIsDefensivelyCopied() throws Exception {
        byte[] credential = new byte[32];
        Arrays.fill(credential, (byte) 8);
        Files.write(dataDirectory.resolve("network.key"), Base64.getEncoder().encode(credential));
        writeAgentConfig("network.key", "survival", 27785);
        PaperNetworkSettings settings = PaperNetworkSettings.load(dataDirectory.toFile());

        byte[] returned = settings.getCredential();
        returned[0] ^= 1;

        assertArrayEquals(credential, settings.getCredential());
    }

    private void writeAgentConfig(String keyFile, String node, int port) throws Exception {
        Files.write(dataDirectory.resolve("network.yml"), (
                "Mode: AGENT\n"
                        + "Node:\n  Id: " + node + "\n"
                        + "Coordinator:\n  Host: 127.0.0.1\n  Port: " + port + "\n"
                        + "Security:\n  KeyFile: " + keyFile + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }
}
