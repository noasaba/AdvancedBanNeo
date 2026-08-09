package me.leoko.advancedban.bukkit.network;

import me.leoko.advancedban.network.protocol.AgentIdentity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Base64;

/** Immutable, fail-closed Paper Agent configuration discovered from network.key. */
public final class PaperNetworkSettings {
    private static final String PAIRING_MARKER = ".agent-paired";
    private static final String AGENT_ID_FILE = "agent.id";
    private static final String KEY_FILE = "network.key";
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 27785;

    private final boolean agent;
    private final String agentId;
    private final String host;
    private final int port;
    private final byte[] credential;
    private final String error;

    private PaperNetworkSettings(boolean agent, String agentId, String host, int port,
                                 byte[] credential, String error) {
        this.agent = agent;
        this.agentId = agentId;
        this.host = host;
        this.port = port;
        this.credential = credential == null ? null : credential.clone();
        this.error = error;
    }

    public static PaperNetworkSettings load(JavaPlugin plugin) {
        return load(plugin.getDataFolder());
    }

    static PaperNetworkSettings load(File pluginDataFolder) {
        File markerFile = new File(pluginDataFolder, PAIRING_MARKER);
        File keyFile = new File(pluginDataFolder, KEY_FILE);
        Path markerPath = markerFile.toPath();
        Path keyPath = keyFile.toPath();
        boolean keyPathExists = Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS);
        boolean markerPathExists = Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS);
        if (!keyPathExists) {
            if (markerPathExists) {
                return invalid(null, DEFAULT_HOST, DEFAULT_PORT,
                        "This server was paired as an Agent but network.key is missing; refusing standalone mode");
            }
            return new PaperNetworkSettings(false, null, null, 0, null, null);
        }

        // Seeing any network.key path is an irreversible automatic pairing decision for this
        // startup lineage. Persist it before validating user-controlled key/config contents so a
        // malformed first copy cannot later disappear and silently promote this server.
        try {
            Files.createDirectories(pluginDataFolder.toPath());
            if (markerPathExists && !Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(null, DEFAULT_HOST, DEFAULT_PORT,
                        ".agent-paired must be a non-symbolic regular file inside the plugin directory");
            }
            if (!markerPathExists) {
                Files.write(markerPath, "paired\n".getBytes(StandardCharsets.US_ASCII),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                markerPathExists = true;
            }
        } catch (IOException exception) {
            return invalid(null, DEFAULT_HOST, DEFAULT_PORT,
                    "Could not persist the Agent pairing marker; refusing standalone mode");
        }

        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        File mainConfig = new File(pluginDataFolder, "config.yml");
        if (Files.isRegularFile(mainConfig.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(mainConfig);
            host = config.getString("Network.CoordinatorHost", DEFAULT_HOST).trim();
            Object configuredPort = config.get("Network.CoordinatorPort");
            if (configuredPort != null) {
                try {
                    port = configuredPort instanceof Number
                            ? ((Number) configuredPort).intValue()
                            : Integer.parseInt(String.valueOf(configuredPort));
                } catch (NumberFormatException exception) {
                    return invalid(null, host, DEFAULT_PORT,
                            "Network CoordinatorPort must be an integer");
                }
            }
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            return invalid(null, host, port, "Network CoordinatorHost or CoordinatorPort is invalid");
        }

        try {
            Files.createDirectories(pluginDataFolder.toPath());
            File dataFolder = pluginDataFolder.getCanonicalFile();
            if (!Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(null, host, port,
                        "network.key must be a non-symbolic regular file inside the AdvancedBan plugin directory");
            }
            File canonicalKey = keyFile.getCanonicalFile();
            if (!canonicalKey.toPath().startsWith(dataFolder.toPath()) || !canonicalKey.isFile()) {
                return invalid(null, host, port,
                        "network.key must be a regular file inside the AdvancedBan plugin directory");
            }
            restrictCredentialPermissions(canonicalKey.toPath());
            String encoded = new String(Files.readAllBytes(canonicalKey.toPath()),
                    StandardCharsets.US_ASCII).trim();
            byte[] credential = Base64.getDecoder().decode(encoded);
            if (credential.length < 32) {
                return invalid(null, host, port, "network.key must contain at least 256 bits");
            }

            String agentId = loadOrCreateAgentId(dataFolder);
            return new PaperNetworkSettings(true, agentId, host, port, credential, null);
        } catch (IOException | IllegalArgumentException exception) {
            return invalid(null, host, port, "network.key or the internal Agent identity could not be read");
        }
    }

    private static String loadOrCreateAgentId(File dataFolder) throws IOException {
        File idFile = new File(dataFolder, AGENT_ID_FILE);
        Path idPath = idFile.toPath();
        if (!Files.exists(idPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.write(idFile.toPath(), (AgentIdentity.generate().toString() + "\n")
                                .getBytes(StandardCharsets.US_ASCII),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another concurrent load created it; validate that value below.
            }
        }
        if (!Files.isRegularFile(idPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("agent.id must be a non-symbolic regular file");
        }
        File canonicalId = idFile.getCanonicalFile();
        if (!canonicalId.toPath().startsWith(dataFolder.toPath()) || !canonicalId.isFile()) {
            throw new IOException("agent.id must remain inside the plugin directory");
        }
        String value = new String(Files.readAllBytes(canonicalId.toPath()),
                StandardCharsets.US_ASCII).trim();
        try {
            return AgentIdentity.parse(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IOException("agent.id is invalid", exception);
        }
    }

    private static void restrictCredentialPermissions(Path keyFile) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFile, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX providers rely on their native ACLs.
        }
    }

    private static PaperNetworkSettings invalid(String agentId, String host, int port, String error) {
        return new PaperNetworkSettings(true, agentId, host, port, null, error);
    }

    public boolean isAgent() { return agent; }

    public boolean isValidAgent() { return agent && credential != null && agentId != null; }

    /** Internal protocol identity. It is generated automatically and is not a credential. */
    public String getNodeId() { return agentId; }

    public String getHost() { return host; }

    public int getPort() { return port; }

    public byte[] getCredential() { return credential == null ? null : credential.clone(); }

    public String getError() { return error; }
}
