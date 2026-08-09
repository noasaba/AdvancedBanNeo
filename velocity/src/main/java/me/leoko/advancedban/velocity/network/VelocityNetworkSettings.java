package me.leoko.advancedban.velocity.network;

import me.leoko.advancedban.velocity.YamlConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileAttribute;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

/** Always-on Velocity Authority transport using one shared network root secret. */
public final class VelocityNetworkSettings {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 27785;
    private static final String AUTHORITY_ID = "velocity";

    private final String host;
    private final int port;
    private final byte[] credential;
    private final boolean credentialGenerated;

    private VelocityNetworkSettings(String host, int port, byte[] credential,
                                    boolean credentialGenerated) {
        this.host = host;
        this.port = port;
        this.credential = credential.clone();
        this.credentialGenerated = credentialGenerated;
    }

    public static VelocityNetworkSettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path dataRoot = dataDirectory.toRealPath();
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        Path mainConfig = dataRoot.resolve("config.yml");
        if (Files.isRegularFile(mainConfig, LinkOption.NOFOLLOW_LINKS)) {
            YamlConfig config = YamlConfig.load(mainConfig);
            host = String.valueOf(value(config, "Network.BindHost", DEFAULT_HOST)).trim();
            port = integer(value(config, "Network.Port", DEFAULT_PORT));
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            throw new IOException("Network BindHost or Port is invalid");
        }

        Path keyFile = dataRoot.resolve("network.key");
        boolean generated = false;
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            createCredentialFile(keyFile, (Base64.getEncoder().encodeToString(secret) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            generated = true;
        }
        if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("network.key must be a regular file inside the plugin directory");
        }
        Path realKey = keyFile.toRealPath();
        if (!realKey.startsWith(dataRoot)) {
            throw new IOException("network.key must not resolve outside the plugin directory");
        }
        restrictCredentialPermissions(realKey);
        byte[] credential;
        try {
            credential = Base64.getDecoder().decode(new String(
                    Files.readAllBytes(realKey), StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("network.key is not valid Base64", exception);
        }
        if (credential.length < 32) {
            throw new IOException("network.key is shorter than 256 bits");
        }
        return new VelocityNetworkSettings(host, port, credential, generated);
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

    private static int integer(Object value) throws IOException {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IOException("Network Port must be an integer", exception);
        }
    }

    public boolean isEnabled() { return true; }

    public String getAuthorityId() { return AUTHORITY_ID; }

    public String getHost() { return host; }

    public int getPort() { return port; }

    public byte[] getCredential() { return credential.clone(); }

    public boolean wasCredentialGenerated() { return credentialGenerated; }
}
