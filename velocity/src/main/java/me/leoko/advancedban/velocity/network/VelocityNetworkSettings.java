package me.leoko.advancedban.velocity.network;

import me.leoko.advancedban.utils.NetworkConfigMigrator;
import me.leoko.advancedban.velocity.YamlConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;

/** Velocity Authority transport configuration controlled by Network.Enabled. */
public final class VelocityNetworkSettings {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 27785;
    private static final String DEFAULT_KEY_FILE = "network.key";
    private static final String AUTHORITY_ID = "velocity";

    private final boolean enabled;
    private final String host;
    private final int port;
    private final byte[] credential;
    private final boolean credentialGenerated;

    private VelocityNetworkSettings(boolean enabled, String host, int port, byte[] credential,
                                    boolean credentialGenerated) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.credential = credential == null ? null : credential.clone();
        this.credentialGenerated = credentialGenerated;
    }

    public static VelocityNetworkSettings load(Path dataDirectory) throws IOException {
        return load(dataDirectory, !Files.isRegularFile(
                dataDirectory.resolve("config.yml"), LinkOption.NOFOLLOW_LINKS));
    }

    public static VelocityNetworkSettings load(Path dataDirectory,
                                               boolean newlyGeneratedConfiguration) throws IOException {
        Files.createDirectories(dataDirectory);
        Path dataRoot = dataDirectory.toRealPath();
        Path configFile = dataRoot.resolve("config.yml");
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            Files.write(configFile, new byte[0], StandardOpenOption.CREATE_NEW);
            newlyGeneratedConfiguration = true;
        }

        LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
        // Before Network.Enabled existed, the Velocity transport always listened.
        defaults.put("Enabled", String.valueOf(!newlyGeneratedConfiguration));
        defaults.put("BindHost", DEFAULT_HOST);
        defaults.put("Port", String.valueOf(DEFAULT_PORT));
        defaults.put("KeyFile", DEFAULT_KEY_FILE);
        NetworkConfigMigrator.ensure(configFile, defaults, Arrays.asList(
                "Paper Agent transport. Enable only when this Velocity should accept Agents.",
                "BindHost must be one hostname or IP address, not CIDR notation.",
                "KeyFile is relative to this AdvancedBan data directory."));

        YamlConfig config = YamlConfig.load(configFile);
        Boolean enabled = strictBoolean(config.get("Network.Enabled"));
        if (enabled == null) {
            throw new IOException("Network.Enabled must be true or false");
        }
        String host = String.valueOf(value(config, "Network.BindHost", DEFAULT_HOST)).trim();
        int port = integer(value(config, "Network.Port", DEFAULT_PORT));
        if (!validHost(host) || port < 1 || port > 65535) {
            throw new IOException("Network BindHost or Port is invalid");
        }
        if (!enabled) {
            return new VelocityNetworkSettings(false, host, port, null, false);
        }

        String keyName = String.valueOf(value(config, "Network.KeyFile", DEFAULT_KEY_FILE)).trim();
        Path relativeKey;
        try {
            relativeKey = Paths.get(keyName);
        } catch (RuntimeException exception) {
            throw new IOException("Network KeyFile is invalid", exception);
        }
        if (keyName.isEmpty() || relativeKey.isAbsolute()) {
            throw new IOException("Network KeyFile must be relative to the AdvancedBan data directory");
        }
        Path keyFile = dataRoot.resolve(relativeKey).normalize();
        if (!keyFile.startsWith(dataRoot)) {
            throw new IOException("Network KeyFile must remain inside the AdvancedBan data directory");
        }

        boolean generated = false;
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = keyFile.getParent();
            if (parent == null || !parent.startsWith(dataRoot)) {
                throw new IOException("Network KeyFile parent is invalid");
            }
            Files.createDirectories(parent);
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            createCredentialFile(keyFile, (Base64.getEncoder().encodeToString(secret) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            generated = true;
        }
        if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Network KeyFile must be a regular non-symbolic file");
        }
        Path realKey = keyFile.toRealPath();
        if (!realKey.startsWith(dataRoot)) {
            throw new IOException("Network KeyFile must not resolve outside the plugin directory");
        }
        restrictCredentialPermissions(realKey);
        byte[] credential;
        try {
            credential = Base64.getDecoder().decode(new String(
                    Files.readAllBytes(realKey), StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Network KeyFile is not valid Base64", exception);
        }
        if (credential.length < 32) {
            throw new IOException("Network KeyFile is shorter than 256 bits");
        }
        return new VelocityNetworkSettings(true, host, port, credential, generated);
    }

    private static void restrictCredentialPermissions(Path keyFile) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFile, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX providers rely on their native ACLs.
        }
    }

    private static void createCredentialFile(Path keyFile, byte[] encoded) throws IOException {
        boolean created = false;
        try {
            try {
                FileAttribute<java.util.Set<PosixFilePermission>> ownerOnly =
                        PosixFilePermissions.asFileAttribute(EnumSet.of(
                                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                Files.createFile(keyFile, ownerOnly);
            } catch (UnsupportedOperationException exception) {
                Files.createFile(keyFile);
            }
            created = true;
            Files.write(keyFile, encoded, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            if (created) {
                try {
                    Files.deleteIfExists(keyFile);
                } catch (IOException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
        }
    }

    private static Object value(YamlConfig config, String path, Object fallback) {
        Object result = config.get(path);
        return result == null ? fallback : result;
    }

    private static Boolean strictBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null && "true".equalsIgnoreCase(String.valueOf(value))) return true;
        if (value != null && "false".equalsIgnoreCase(String.valueOf(value))) return false;
        return null;
    }

    private static int integer(Object value) throws IOException {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IOException("Network Port must be an integer", exception);
        }
    }

    private static boolean validHost(String host) {
        return host != null && !host.isEmpty() && host.indexOf('/') < 0
                && host.indexOf(' ') < 0 && host.indexOf('\t') < 0;
    }

    public boolean isEnabled() { return enabled; }
    public String getAuthorityId() { return AUTHORITY_ID; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public byte[] getCredential() { return credential == null ? null : credential.clone(); }
    public boolean wasCredentialGenerated() { return credentialGenerated; }
}
