package me.leoko.advancedban.bukkit.network;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/** Immutable, fail-closed Paper Agent configuration. */
public final class PaperNetworkSettings {
    private static final String PAIRING_MARKER = ".agent-paired";
    private final boolean agent;
    private final String nodeId;
    private final String host;
    private final int port;
    private final byte[] credential;
    private final String error;

    private PaperNetworkSettings(boolean agent, String nodeId, String host, int port,
                                 byte[] credential, String error) {
        this.agent = agent;
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.credential = credential == null ? null : credential.clone();
        this.error = error;
    }

    public static PaperNetworkSettings load(JavaPlugin plugin) {
        return load(plugin.getDataFolder());
    }

    static PaperNetworkSettings load(File pluginDataFolder) {
        File configFile = new File(pluginDataFolder, "network.yml");
        File markerFile = new File(pluginDataFolder, PAIRING_MARKER);
        File defaultCredential = new File(pluginDataFolder, "network.key");
        if (!configFile.isFile()) {
            if (markerFile.isFile() || defaultCredential.isFile()) {
                return invalid(null, null, 0,
                        "Paired Agent state exists but network.yml is missing; refusing standalone mode");
            }
            return new PaperNetworkSettings(false, null, null, 0, null, null);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String mode = config.getString("Mode", "STANDALONE").trim();
        if (!"AGENT".equalsIgnoreCase(mode)) {
            if (markerFile.isFile() || defaultCredential.isFile()) {
                return invalid(null, null, 0,
                        "Paired Agent state remains; remove network.yml, network.key, and .agent-paired to unpair");
            }
            return new PaperNetworkSettings(false, null, null, 0, null, null);
        }

        try {
            Files.createDirectories(pluginDataFolder.toPath());
            if (!markerFile.isFile()) {
                Files.write(markerFile.toPath(), "paired\n".getBytes(StandardCharsets.US_ASCII));
            }
        } catch (IOException exception) {
            return invalid(null, null, 0, "Could not persist the Agent pairing marker");
        }

        String nodeId = config.getString("Node.Id", "").trim();
        String host = config.getString("Coordinator.Host", "127.0.0.1").trim();
        int port = config.getInt("Coordinator.Port", 27785);
        String keyName = config.getString("Security.KeyFile", "network.key").trim();
        if (!nodeId.matches("[A-Za-z0-9_-]{1,64}")) {
            return invalid(nodeId, host, port, "Node.Id must match [A-Za-z0-9_-]{1,64}");
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            return invalid(nodeId, host, port, "Coordinator host or port is invalid");
        }

        try {
            File dataFolder = pluginDataFolder.getCanonicalFile();
            File keyFile = new File(dataFolder, keyName).getCanonicalFile();
            if (!keyFile.toPath().startsWith(dataFolder.toPath()) || !keyFile.isFile()) {
                return invalid(nodeId, host, port, "Network credential file is missing or outside the plugin directory");
            }
            String encoded = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.US_ASCII).trim();
            byte[] credential = Base64.getDecoder().decode(encoded);
            if (credential.length < 32) {
                return invalid(nodeId, host, port, "Network credential must contain at least 256 bits");
            }
            return new PaperNetworkSettings(true, nodeId, host, port, credential, null);
        } catch (IOException | IllegalArgumentException exception) {
            return invalid(nodeId, host, port, "Network credential could not be read");
        }
    }

    private static PaperNetworkSettings invalid(String nodeId, String host, int port, String error) {
        return new PaperNetworkSettings(true, nodeId, host, port, null, error);
    }

    public boolean isAgent() {
        return agent;
    }

    public boolean isValidAgent() {
        return agent && credential != null;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public byte[] getCredential() {
        return credential == null ? null : credential.clone();
    }

    public String getError() {
        return error;
    }
}
