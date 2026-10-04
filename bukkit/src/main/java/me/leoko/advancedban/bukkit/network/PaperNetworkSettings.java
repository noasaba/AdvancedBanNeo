package me.leoko.advancedban.bukkit.network;

import me.leoko.advancedban.network.protocol.AgentIdentity;
import me.leoko.advancedban.utils.NetworkConfigMigrator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;

/** Immutable, fail-closed Paper Agent configuration controlled by Network.Enabled. */
public final class PaperNetworkSettings {
    private static final String PAIRING_MARKER = ".agent-paired";
    private static final String AGENT_ID_FILE = "agent.id";
    private static final String DEFAULT_KEY_FILE = "network.key";
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 27785;

    private final boolean agent;
    private final boolean failClosed;
    private final String agentId;
    private final String host;
    private final int port;
    private final byte[] credential;
    private final String error;

    private PaperNetworkSettings(boolean agent, boolean failClosed, String agentId, String host,
                                 int port, byte[] credential, String error) {
        this.agent = agent;
        this.failClosed = failClosed;
        this.agentId = agentId;
        this.host = host;
        this.port = port;
        this.credential = credential == null ? null : credential.clone();
        this.error = error;
    }

    public static PaperNetworkSettings load(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        boolean newConfiguration = !configFile.isFile();
        if (newConfiguration) {
            plugin.saveResource("config.yml", false);
        }
        return load(plugin.getDataFolder(), newConfiguration);
    }

    static PaperNetworkSettings load(File pluginDataFolder) {
        File configFile = new File(pluginDataFolder, "config.yml");
        boolean newConfiguration = !configFile.isFile();
        if (newConfiguration) {
            try {
                Files.createDirectories(pluginDataFolder.toPath());
                Files.write(configFile.toPath(), new byte[0], StandardOpenOption.CREATE_NEW);
            } catch (IOException exception) {
                return invalid(true, null, DEFAULT_HOST, DEFAULT_PORT,
                        "Could not create config.yml");
            }
        }
        return load(pluginDataFolder, newConfiguration);
    }

    private static PaperNetworkSettings load(File pluginDataFolder, boolean newConfiguration) {
        Path dataPath = pluginDataFolder.toPath();
        Path configPath = dataPath.resolve("config.yml");
        Path markerPath = dataPath.resolve(PAIRING_MARKER);
        boolean legacyPaired = Files.exists(dataPath.resolve(DEFAULT_KEY_FILE), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS);
        LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
        defaults.put("Enabled", String.valueOf(!newConfiguration && legacyPaired));
        defaults.put("CoordinatorHost", DEFAULT_HOST);
        defaults.put("CoordinatorPort", String.valueOf(DEFAULT_PORT));
        defaults.put("KeyFile", DEFAULT_KEY_FILE);
        defaults.put("FailClosed", "false");
        try {
            NetworkConfigMigrator.ensure(configPath, defaults, Arrays.asList(
                    "Velocity Authority connection. Leave disabled for standalone Paper.",
                    "Enable this only after copying the Authority's network.key file here.",
                    "CoordinatorHost must be one hostname or IP address, not CIDR notation."));
        } catch (IOException exception) {
            return invalid(true, null, DEFAULT_HOST, DEFAULT_PORT,
                    "Could not migrate the Network configuration");
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configPath.toFile());
        Boolean enabled = strictBoolean(config.get("Network.Enabled"));
        Boolean failClosed = strictBoolean(config.get("Network.FailClosed"));
        if (enabled == null || failClosed == null) {
            return invalid(true, null, DEFAULT_HOST, DEFAULT_PORT,
                    "Network.Enabled and Network.FailClosed must be true or false");
        }
        if (!enabled) {
            return new PaperNetworkSettings(false, failClosed, null, null, 0, null, null);
        }

        String host = config.getString("Network.CoordinatorHost", DEFAULT_HOST).trim();
        int port;
        try {
            port = strictPort(config.get("Network.CoordinatorPort"), DEFAULT_PORT);
        } catch (IllegalArgumentException exception) {
            return invalid(failClosed, null, host, DEFAULT_PORT,
                    "Network CoordinatorPort must be an integer");
        }
        if (!validHost(host) || port < 1 || port > 65535) {
            return invalid(failClosed, null, host, port,
                    "Network CoordinatorHost or CoordinatorPort is invalid");
        }

        String keyName = config.getString("Network.KeyFile", DEFAULT_KEY_FILE).trim();
        Path relativeKey;
        try {
            relativeKey = Paths.get(keyName);
        } catch (RuntimeException exception) {
            return invalid(failClosed, null, host, port, "Network KeyFile is invalid");
        }
        if (keyName.isEmpty() || relativeKey.isAbsolute()) {
            return invalid(failClosed, null, host, port,
                    "Network KeyFile must be relative to the AdvancedBan data directory");
        }

        try {
            Files.createDirectories(dataPath);
            File dataFolder = pluginDataFolder.getCanonicalFile();
            Path keyPath = dataFolder.toPath().resolve(relativeKey).normalize();
            if (!keyPath.startsWith(dataFolder.toPath())
                    || !Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)) {
                persistPairingMarker(markerPath);
                return invalid(failClosed, null, host, port,
                        "Network KeyFile is missing or outside the AdvancedBan data directory");
            }
            persistPairingMarker(markerPath);
            Path canonicalKey = keyPath.toRealPath();
            if (!canonicalKey.startsWith(dataFolder.toPath())) {
                return invalid(failClosed, null, host, port,
                        "Network KeyFile must remain inside the AdvancedBan data directory");
            }
            restrictCredentialPermissions(canonicalKey);
            byte[] credential = Base64.getDecoder().decode(new String(
                    Files.readAllBytes(canonicalKey), StandardCharsets.US_ASCII).trim());
            if (credential.length < 32) {
                return invalid(failClosed, null, host, port,
                        "Network KeyFile must contain at least 256 bits");
            }
            String agentId = loadOrCreateAgentId(dataFolder);
            return new PaperNetworkSettings(true, failClosed, agentId, host, port,
                    credential, null);
        } catch (IOException | IllegalArgumentException exception) {
            return invalid(failClosed, null, host, port,
                    "Network KeyFile or the internal Agent identity could not be read");
        }
    }

    private static void persistPairingMarker(Path markerPath) throws IOException {
        if (Files.exists(markerPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid Agent pairing marker");
            }
            return;
        }
        Files.write(markerPath, "paired\n".getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String loadOrCreateAgentId(File dataFolder) throws IOException {
        Path idPath = dataFolder.toPath().resolve(AGENT_ID_FILE);
        if (!Files.exists(idPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.write(idPath, (AgentIdentity.generate().toString() + "\n")
                                .getBytes(StandardCharsets.US_ASCII),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another concurrent load created it; validate that value below.
            }
        }
        if (!Files.isRegularFile(idPath, LinkOption.NOFOLLOW_LINKS)
                || !idPath.toRealPath().startsWith(dataFolder.toPath())) {
            throw new IOException("agent.id must be a regular file inside the plugin directory");
        }
        String value = new String(Files.readAllBytes(idPath), StandardCharsets.US_ASCII).trim();
        return AgentIdentity.parse(value).toString();
    }

    private static void restrictCredentialPermissions(Path keyFile) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFile, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX providers rely on their native ACLs.
        }
    }

    private static Boolean strictBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null && "true".equalsIgnoreCase(String.valueOf(value))) return true;
        if (value != null && "false".equalsIgnoreCase(String.valueOf(value))) return false;
        return null;
    }

    private static int strictPort(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static boolean validHost(String host) {
        return host != null && !host.trim().isEmpty() && host.indexOf('/') < 0
                && host.indexOf(' ') < 0 && host.indexOf('\t') < 0;
    }

    private static PaperNetworkSettings invalid(boolean failClosed, String agentId,
                                                String host, int port, String error) {
        return new PaperNetworkSettings(true, failClosed, agentId, host, port, null, error);
    }

    public boolean isAgent() { return agent; }
    public boolean isValidAgent() { return agent && credential != null && agentId != null; }
    public boolean isFailClosed() { return failClosed; }
    public String getNodeId() { return agentId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public byte[] getCredential() { return credential == null ? null : credential.clone(); }
    public String getError() { return error; }
}
