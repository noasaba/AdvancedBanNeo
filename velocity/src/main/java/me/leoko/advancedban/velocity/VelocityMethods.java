package me.leoko.advancedban.velocity;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import me.leoko.advancedban.MethodInterface;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.UUIDManager;
import me.leoko.advancedban.utils.Permissionable;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.tabcompletion.TabCompleter;
import me.leoko.advancedban.velocity.event.PunishmentEvent;
import me.leoko.advancedban.velocity.event.RevokePunishmentEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class VelocityMethods implements MethodInterface {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final VelocityMain plugin;
    private final ProxyServer proxy;
    private final Path dataDirectory;
    private YamlConfig config;
    private YamlConfig messages;
    private YamlConfig layouts;
    private YamlConfig mysql;
    private boolean luckPermsAvailable;

    VelocityMethods(VelocityMain plugin, ProxyServer proxy, Path dataDirectory) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public void loadFiles() {
        try {
            Files.createDirectories(dataDirectory);
            Path configFile = copyDefault("config.yml");
            Path messageFile = copyDefault("Messages.yml");
            Path layoutFile = copyDefault("Layouts.yml");
            Path mysqlFile = dataDirectory.resolve("MySQL.yml");
            config = YamlConfig.load(configFile);
            messages = YamlConfig.load(messageFile);
            layouts = YamlConfig.load(layoutFile);
            mysql = Files.exists(mysqlFile) ? YamlConfig.load(mysqlFile) : config;
            luckPermsAvailable = proxy.getPluginManager().isLoaded("luckperms");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load AdvancedBan configuration", exception);
        }
    }

    private Path copyDefault(String name) throws IOException {
        Path destination = dataDirectory.resolve(name);
        if (!Files.exists(destination)) {
            try (InputStream source = plugin.getClass().getClassLoader().getResourceAsStream(name)) {
                if (source == null) {
                    throw new IOException("Missing bundled resource " + name);
                }
                Files.copy(source, destination);
            }
        }
        return destination;
    }

    @Override
    public String getFromUrlJson(String url, String key) {
        HttpURLConnection request = null;
        try {
            request = (HttpURLConnection) new URL(url).openConnection();
            request.setConnectTimeout(Universal.HTTP_TIMEOUT_MILLIS);
            request.setReadTimeout(Universal.HTTP_TIMEOUT_MILLIS);
            try (InputStreamReader reader = new InputStreamReader(request.getInputStream())) {
                JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                String[] keys = key.split("\\|");
                for (int i = 0; i < keys.length - 1; i++) {
                    json = json.getAsJsonObject(keys[i]);
                }
                JsonElement value = json.get(keys[keys.length - 1]);
                return value == null || value.isJsonNull() ? null : value.getAsString();
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (request != null) {
                request.disconnect();
            }
        }
    }

    @Override
    public String getVersion() {
        return proxy.getPluginManager().fromInstance(plugin)
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("2.3.0");
    }

    @Override
    public String[] getKeys(Object file, String path) {
        return config(file).keys(path).toArray(new String[0]);
    }

    @Override
    public Object getConfig() {
        return config;
    }

    @Override
    public Object getMessages() {
        return messages;
    }

    @Override
    public Object getLayouts() {
        return layouts;
    }

    @Override
    public void setupMetrics() {
        // Metrics are intentionally optional on this new platform adapter. The
        // AdvancedBan runtime must not depend on an external Velocity plugin.
    }

    @Override
    public boolean isBungee() {
        // Core uses this as "proxy mode" (online UUID lookup and proxy version
        // display), so Velocity must retain the historical true value.
        return true;
    }

    @Override
    public String clearFormatting(String text) {
        return PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(text));
    }

    @Override
    public Object getPlugin() {
        return plugin;
    }

    @Override
    public File getDataFolder() {
        return dataDirectory.toFile();
    }

    @Override
    public void setCommandExecutor(String cmd, String permission, TabCompleter tabCompleter) {
        SimpleCommand command = new VelocityCommand(cmd, permission, tabCompleter);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder(cmd).plugin(plugin).build(),
                command
        );
    }

    @Override
    public void sendMessage(Object player, String msg) {
        ((CommandSource) player).sendMessage(LEGACY.deserialize(msg));
    }

    @Override
    public String getName(Object player) {
        return player instanceof Player ? ((Player) player).getUsername() : "CONSOLE";
    }

    @Override
    public String getName(String uuid) {
        try {
            return proxy.getPlayer(UUID.fromString(uuid)).map(Player::getUsername).orElse(null);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public String getIP(Object player) {
        return ((Player) player).getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public String getInternUUID(Object player) {
        return player instanceof Player
                ? ((Player) player).getUniqueId().toString().replace("-", "")
                : "none";
    }

    @Override
    public String getInternUUID(String player) {
        return proxy.getPlayer(player)
                .map(Player::getUniqueId)
                .map(UUID::toString)
                .map(value -> value.replace("-", ""))
                .orElse(null);
    }

    @Override
    public boolean hasPerms(Object player, String perms) {
        return player instanceof CommandSource && ((CommandSource) player).hasPermission(perms);
    }

    @Override
    public Permissionable getOfflinePermissionPlayer(String name) {
        if (!luckPermsAvailable) {
            return permission -> false;
        }
        try {
            UserManager manager = LuckPermsProvider.get().getUserManager();
            UUID uuid = manager.lookupUniqueId(name).join();
            User user = uuid == null ? null : manager.loadUser(uuid).join();
            return permission -> user != null
                    && user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        } catch (RuntimeException exception) {
            Universal.get().debugException(exception);
            return permission -> false;
        }
    }

    @Override
    public boolean isOnline(String name) {
        return proxy.getPlayer(name).isPresent();
    }

    @Override
    public Object getPlayer(String name) {
        return proxy.getPlayer(name).orElse(null);
    }

    @Override
    public void kickPlayer(String player, String reason) {
        proxy.getPlayer(player).ifPresent(value -> value.disconnect(LEGACY.deserialize(reason)));
    }

    @Override
    public Object[] getOnlinePlayers() {
        return proxy.getAllPlayers().toArray();
    }

    @Override
    public void scheduleAsyncRep(Runnable runnable, long delay, long period) {
        proxy.getScheduler().buildTask(plugin, runnable)
                .delay(delay * 50, TimeUnit.MILLISECONDS)
                .repeat(period * 50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    @Override
    public void scheduleAsync(Runnable runnable, long delay) {
        proxy.getScheduler().buildTask(plugin, runnable)
                .delay(delay * 50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    @Override
    public void runAsync(Runnable runnable) {
        proxy.getScheduler().buildTask(plugin, runnable).schedule();
    }

    @Override
    public void runSync(Runnable runnable) {
        // Velocity has no Bukkit-style main thread. Its API is designed for
        // concurrent use, so immediate execution is the closest equivalent.
        runnable.run();
    }

    @Override
    public void executeCommand(String cmd) {
        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), cmd);
    }

    @Override
    public boolean callChat(Object player) {
        Punishment punishment = PunishmentManager.get().getMute(UUIDManager.get().getUUID(getName(player)));
        if (punishment == null) {
            return false;
        }
        punishment.getLayout().forEach(line -> sendMessage(player, line));
        return true;
    }

    @Override
    public boolean callCMD(Object player, String cmd) {
        Punishment punishment;
        if (Universal.get().isMuteCommand(cmd.substring(1))
                && (punishment = PunishmentManager.get().getMute(UUIDManager.get().getUUID(getName(player)))) != null) {
            punishment.getLayout().forEach(line -> sendMessage(player, line));
            return true;
        }
        return false;
    }

    @Override
    public Object getMySQLFile() {
        return mysql;
    }

    @Override
    public String parseJSON(InputStreamReader json, String key) {
        JsonElement element = new JsonParser().parse(json);
        if (element instanceof JsonNull || !element.isJsonObject()) {
            return null;
        }
        JsonElement value = element.getAsJsonObject().get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Override
    public String parseJSON(String json, String key) {
        JsonElement element = new JsonParser().parse(json);
        if (element instanceof JsonNull || !element.isJsonObject()) {
            return null;
        }
        JsonElement value = element.getAsJsonObject().get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Override
    public Boolean getBoolean(Object file, String path) {
        Object value = config(file).get(path);
        return value instanceof Boolean ? (Boolean) value : Boolean.valueOf(String.valueOf(value));
    }

    @Override
    public String getString(Object file, String path) {
        Object value = config(file).get(path);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public Long getLong(Object file, String path) {
        Object value = config(file).get(path);
        return value == null ? 0L
                : value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value));
    }

    @Override
    public Integer getInteger(Object file, String path) {
        Object value = config(file).get(path);
        return value == null ? 0
                : value instanceof Number ? ((Number) value).intValue() : Integer.valueOf(String.valueOf(value));
    }

    @Override
    public List<String> getStringList(Object file, String path) {
        return config(file).stringList(path);
    }

    @Override
    public boolean getBoolean(Object file, String path, boolean def) {
        return contains(file, path) ? getBoolean(file, path) : def;
    }

    @Override
    public String getString(Object file, String path, String def) {
        return contains(file, path) ? getString(file, path) : def;
    }

    @Override
    public long getLong(Object file, String path, long def) {
        return contains(file, path) ? getLong(file, path) : def;
    }

    @Override
    public int getInteger(Object file, String path, int def) {
        return contains(file, path) ? getInteger(file, path) : def;
    }

    @Override
    public boolean contains(Object file, String path) {
        return config(file).get(path) != null;
    }

    @Override
    public String getFileName(Object file) {
        return config(file).fileName();
    }

    @Override
    public void callPunishmentEvent(Punishment punishment) {
        proxy.getEventManager().fireAndForget(new PunishmentEvent(punishment));
    }

    @Override
    public void callRevokePunishmentEvent(Punishment punishment, boolean massClear) {
        proxy.getEventManager().fireAndForget(new RevokePunishmentEvent(punishment, massClear));
    }

    @Override
    public boolean isOnlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    @Override
    public void notify(String perm, List<String> notification) {
        proxy.getAllPlayers().stream()
                .filter(player -> Universal.get().hasPerms(player, perm))
                .forEach(player -> notification.forEach(line -> sendMessage(player, line)));
    }

    @Override
    public void log(String msg) {
        proxy.getConsoleCommandSource().sendMessage(LEGACY.deserialize(msg.replace('&', '§')));
    }

    @Override
    public boolean isUnitTesting() {
        return false;
    }

    private static YamlConfig config(Object value) {
        if (!(value instanceof YamlConfig)) {
            throw new IllegalArgumentException("Not an AdvancedBan Velocity configuration");
        }
        return (YamlConfig) value;
    }
}
