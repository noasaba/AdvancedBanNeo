package me.leoko.advancedban.velocity.network;

import me.leoko.advancedban.velocity.YamlConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinator configuration and per-node credentials. */
public final class VelocityNetworkSettings {
    private static final String DEFAULT_CONFIG =
            "# AdvancedBan Neo Authority/Agent transport\n"
                    + "Enabled: false\n"
                    + "Authority:\n"
                    + "  Id: velocity\n"
                    + "Listen:\n"
                    + "  Host: 127.0.0.1\n"
                    + "  Port: 27785\n"
                    + "Security:\n"
                    + "  CredentialsDirectory: nodes\n"
                    + "AllowedNodes: []\n";

    private final boolean enabled;
    private final String authorityId;
    private final String host;
    private final int port;
    private final Map<String, byte[]> credentials;

    private VelocityNetworkSettings(boolean enabled, String authorityId, String host, int port,
                                    Map<String, byte[]> credentials) {
        this.enabled = enabled;
        this.authorityId = authorityId;
        this.host = host;
        this.port = port;
        this.credentials = credentials;
    }

    public static VelocityNetworkSettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path dataRoot = dataDirectory.toRealPath();
        Path configFile = dataRoot.resolve("network.yml");
        if (!Files.exists(configFile)) {
            Files.write(configFile, DEFAULT_CONFIG.getBytes(StandardCharsets.UTF_8));
        }
        YamlConfig config = YamlConfig.load(configFile);
        boolean enabled = Boolean.parseBoolean(String.valueOf(value(config, "Enabled", false)));
        String authorityId = String.valueOf(value(config, "Authority.Id", "velocity")).trim();
        String host = String.valueOf(value(config, "Listen.Host", "127.0.0.1")).trim();
        int port = integer(value(config, "Listen.Port", 27785), 27785);
        String credentialDirectory = String.valueOf(value(
                config, "Security.CredentialsDirectory", "nodes")).trim();

        if (!authorityId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IOException("Authority.Id is invalid");
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            throw new IOException("Coordinator listen host or port is invalid");
        }

        Path credentialRoot = dataRoot.resolve(credentialDirectory).normalize();
        if (!credentialRoot.startsWith(dataRoot)) {
            throw new IOException("CredentialsDirectory must remain inside the plugin directory");
        }
        Files.createDirectories(credentialRoot);
        credentialRoot = credentialRoot.toRealPath();
        if (!credentialRoot.startsWith(dataRoot)) {
            throw new IOException("CredentialsDirectory must not resolve outside the plugin directory");
        }

        Map<String, byte[]> credentials = new LinkedHashMap<>();
        List<String> nodes = config.stringList("AllowedNodes");
        for (String node : nodes) {
            String nodeId = node.trim();
            if (!nodeId.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IOException("Invalid AllowedNodes entry");
            }
            Path keyFile = credentialRoot.resolve(nodeId + ".key");
            if (!Files.exists(keyFile)) {
                byte[] generated = new byte[32];
                new SecureRandom().nextBytes(generated);
                Files.write(keyFile, (Base64.getEncoder().encodeToString(generated) + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            keyFile = keyFile.toRealPath();
            if (!keyFile.startsWith(credentialRoot) || !Files.isRegularFile(keyFile)) {
                throw new IOException("Credential for node " + nodeId
                        + " must be a regular file inside CredentialsDirectory");
            }
            restrictCredentialPermissions(keyFile);
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(new String(
                        Files.readAllBytes(keyFile), StandardCharsets.US_ASCII).trim());
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid credential for node " + nodeId, exception);
            }
            if (decoded.length < 32) {
                throw new IOException("Credential for node " + nodeId + " is shorter than 256 bits");
            }
            credentials.put(nodeId, decoded);
        }
        return new VelocityNetworkSettings(enabled, authorityId, host, port,
                Collections.unmodifiableMap(credentials));
    }

    private static void restrictCredentialPermissions(Path keyFile) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFile, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX providers rely on their native ACLs.
        }
    }

    private static Object value(YamlConfig config, String path, Object fallback) {
        Object result = config.get(path);
        return result == null ? fallback : result;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAuthorityId() {
        return authorityId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Map<String, byte[]> getCredentials() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        credentials.forEach((node, key) -> copy.put(node, key.clone()));
        return copy;
    }

    public boolean isAllowedNode(String nodeId) {
        return nodeId != null && credentials.containsKey(nodeId);
    }
}
