package me.leoko.advancedban.bukkit;

import me.leoko.advancedban.MethodInterface;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.bukkit.event.PunishmentEvent;
import me.leoko.advancedban.bukkit.event.RevokePunishmentEvent;
import me.leoko.advancedban.bukkit.listener.CommandReceiver;
import me.leoko.advancedban.bukkit.network.PaperAgentClient;
import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.UUIDManager;
import me.leoko.advancedban.utils.Permissionable;
import me.leoko.advancedban.utils.FloodgateIdentity;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.tabcompletion.TabCompleter;
import me.leoko.advancedban.network.protocol.AuthorityRequest;
import me.leoko.advancedban.network.protocol.AuthorityRequestCodec;
import me.leoko.advancedban.runtime.RuntimeRole;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.FutureTask;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Created by Leoko @ dev.skamps.eu on 23.07.2016.
 */
public class BukkitMethods implements MethodInterface {

    private final File messageFile = new File(getDataFolder(), "Messages.yml");
    private final File layoutFile = new File(getDataFolder(), "Layouts.yml");
    private final File mysqlFile = new File(getDataFolder(), "MySQL.yml");
    private YamlConfiguration config;
    private File configFile = new File(getDataFolder(), "config.yml");
    private YamlConfiguration messages;
    private YamlConfiguration layouts;
    private YamlConfiguration mysql;
    private BiFunction<OfflinePlayer, String, Boolean> permissionVault;
    private final RuntimeRole runtimeRole;
    private final boolean agentFailClosed;
    private volatile PaperAgentClient agentClient;
    private final ConcurrentMap<String, CompletionEntry> authorityCompletions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> pendingCompletions = new ConcurrentHashMap<>();

    public BukkitMethods() {
        this(RuntimeRole.STANDALONE_AUTHORITY, true);
    }

    public BukkitMethods(RuntimeRole runtimeRole) {
        this(runtimeRole, true);
    }

    public BukkitMethods(RuntimeRole runtimeRole, boolean agentFailClosed) {
        this.runtimeRole = runtimeRole;
        this.agentFailClosed = agentFailClosed;
        // Vault support
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> rsp = Bukkit.getServer().getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
            permissionVault = (player, perms) -> rsp.getProvider().playerHas(null, player, perms);
        }
    }

    @Override
    public RuntimeRole getRuntimeRole() {
        return runtimeRole;
    }

    @Override
    public boolean isAgentFailClosed() {
        return agentFailClosed;
    }

    public void setAgentClient(PaperAgentClient agentClient) {
        this.agentClient = agentClient;
    }

    @Override
    public void loadFiles() {
        if (!configFile.exists()) {
            getPlugin().saveResource("config.yml", true);
        }
        if (!messageFile.exists()) {
            getPlugin().saveResource("Messages.yml", true);
        }
        if (!layoutFile.exists()) {
            getPlugin().saveResource("Layouts.yml", true);
        }

        try {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));
            messages = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(messageFile), StandardCharsets.UTF_8));
            layouts = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(layoutFile), StandardCharsets.UTF_8));

            if (mysqlFile.exists()) {
                mysql = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(mysqlFile), StandardCharsets.UTF_8));
            } else {
                mysql = YamlConfiguration.loadConfiguration(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));
            }
        } catch (FileNotFoundException exc) {
            // We just saved the files, so that should really not happen.
            Universal.get().debugException(exc);
        }
    }

    @Override
    public String getFromUrlJson(String url, String key) {
        try {
            HttpURLConnection request = (HttpURLConnection) new URL(url).openConnection();
            request.setConnectTimeout(Universal.HTTP_TIMEOUT_MILLIS);
            request.setReadTimeout(Universal.HTTP_TIMEOUT_MILLIS);
            request.connect();

            JSONParser jp = new JSONParser();
            JSONObject json = (JSONObject) jp.parse(new InputStreamReader(request.getInputStream()));

            String[] keys = key.split("\\|");
            for (int i = 0; i < keys.length - 1; i++) {
                json = (JSONObject) json.get(keys[i]);
            }

            return json.get(keys[keys.length - 1]).toString();
        } catch (Exception exc) {
            return null;
        }
    }

    @Override
    public String getVersion() {
        return getPlugin().getDescription().getVersion();
    }

    @Override
    public String[] getKeys(Object file, String path) {
        return ((YamlConfiguration) file).getConfigurationSection(path).getKeys(false).toArray(new String[0]);
    }

    @Override
    public YamlConfiguration getConfig() {
        return config;
    }

    @Override
    public YamlConfiguration getMessages() {
        return messages;
    }

    @Override
    public YamlConfiguration getLayouts() {
        return layouts;
    }

    @Override
    public void setupMetrics() {
        Metrics metrics = new Metrics(getPlugin());
        metrics.addCustomChart(new Metrics.SimplePie("MySQL", () ->
                runtimeRole.isAuthority() && DatabaseManager.get().isUseMySQL() ? "yes" : "no"));
    }

    @Override
    public boolean isBungee() {
        return false;
    }

    @Override
    public String clearFormatting(String text) {
        return ChatColor.stripColor(text);
    }

    @Override
    public JavaPlugin getPlugin() {
        return BukkitMain.get();
    }

    @Override
    public File getDataFolder() {
        return getPlugin().getDataFolder();
    }

    @Override
    public void setCommandExecutor(String cmd, String permission, TabCompleter tabCompleter) {
        setCommandExecutor(cmd, tabCompleter);
    }

    @Override
    public void setCommandExecutor(String cmd, TabCompleter tabCompleter) {
        boolean friendly = getBoolean(getConfig(), "Friendly Register Commands", false);
        PluginCommand command = (friendly) ? getPlugin().getCommand(cmd) : Bukkit.getPluginCommand(cmd);
        if (command != null) {
            command.setExecutor(CommandReceiver.get());
            if (tabCompleter != null)
                command.setTabCompleter((commandSender, c, s, args) -> {
                    if (!runtimeRole.isAgent() && command.getPermission() != null
                            && !hasPerms(commandSender, command.getPermission()))
                        return Collections.emptyList();
                    if (!runtimeRole.isAgent()) {
                        return tabCompleter.onTabComplete(commandSender, args);
                    }
                    return getAuthorityCompletions(commandSender, cmd, args);
                });
        } else {
            System.out.println("AdvancedBan >> Failed to register command " + cmd);
        }
    }

    @Override
    public void sendMessage(Object player, String msg) {
        callSync(() -> {
            ((CommandSender) player).sendMessage(msg);
            return null;
        });
    }

    @Override
    public boolean hasPerms(Object player, String perms) {
        return callSync(() -> ((CommandSender) player).hasPermission(perms));
    }

    @Override
    public Permissionable getOfflinePermissionPlayer(String name) {
        OfflinePlayer player = callSync(() -> Bukkit.getOfflinePlayer(name));
        if (permissionVault == null || player == null || !callSync(player::hasPlayedBefore))
            return permission -> false;

        return permission -> callSync(() -> permissionVault.apply(player, permission));
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isOnline(String name) {
        return callSync(() -> Bukkit.getOfflinePlayer(name).isOnline());
    }

    @Override
    public Player getPlayer(String name) {
        return callSync(() -> Bukkit.getPlayer(name));
    }

    @Override
    public void kickPlayer(String player, String reason) {
        callSync(() -> {
            Player target = Bukkit.getPlayer(player);
            if (target != null && target.isOnline()) {
                target.kickPlayer(reason);
            }
            return null;
        });
    }

    @Override
    public Player[] getOnlinePlayers() {
        return callSync(() -> Bukkit.getOnlinePlayers().toArray(new Player[]{}));
    }

    @Override
    public void scheduleAsyncRep(Runnable rn, long l1, long l2) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(getPlugin(), rn, l1, l2);
    }

    @Override
    public void scheduleAsync(Runnable rn, long l1) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(getPlugin(), rn, l1);
    }

    @Override
    public void runAsync(Runnable rn) {
        Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), rn);
    }

    @Override
    public void runSync(Runnable rn) {
        Bukkit.getScheduler().runTask(getPlugin(), rn);
    }

    @Override
    public void executeCommand(String cmd) {
        callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
    }

    @Override
    public String getName(Object player) {
        return callSync(() -> ((CommandSender) player).getName());
    }

    @Override
    public String getName(String uuid) {
        UUID parsed = UUIDManager.get().fromString(uuid);
        return parsed == null ? null : callSync(() -> Bukkit.getOfflinePlayer(parsed).getName());
    }

    @Override
    public String getIP(Object player) {
        return callSync(() -> ((Player) player).getAddress().getAddress().getHostAddress());
    }

    @Override
    public String getInternUUID(Object player) {
        return callSync(() -> player instanceof OfflinePlayer
                ? ((OfflinePlayer) player).getUniqueId().toString().replaceAll("-", "") : "none");
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getInternUUID(String player) {
        return callSync(() -> Bukkit.getOfflinePlayer(player).getUniqueId().toString().replaceAll("-", ""));
    }

    @Override
    public boolean callChat(Object player) {
        Punishment pnt = PunishmentManager.get().getRuntimeMute(getInternUUID(player));
        if (pnt != null) {
            pnt.getLayout().forEach(str -> sendMessage(player, str));
            return true;
        }
        return false;
    }

    @Override
    public boolean callCMD(Object player, String cmd) {
        if (cmd == null || cmd.length() < 2) {
            return false;
        }
        Punishment pnt;
        if (Universal.get().isMuteCommand(cmd.substring(1))
                && (pnt = PunishmentManager.get().getRuntimeMute(getInternUUID(player))) != null) {
            pnt.getLayout().forEach(str -> sendMessage(player, str));
            return true;
        }
        return false;
    }

    @Override
    public YamlConfiguration getMySQLFile() {
        return mysql;
    }

    @Override
    public String parseJSON(InputStreamReader json, String key) {
        try {
            return ((JSONObject) new JSONParser().parse(json)).get(key).toString();
        } catch (ParseException | IOException e) {
            System.out.println("Error -> " + e.getMessage());
            return null;
        }
    }

    @Override
    public String parseJSON(String json, String key) {
        try {
            return ((JSONObject) new JSONParser().parse(json)).get(key).toString();
        } catch (ParseException e) {
            return null;
        }
    }

    @Override
    public Boolean getBoolean(Object file, String path) {
        return ((YamlConfiguration) file).getBoolean(path);
    }

    @Override
    public String getString(Object file, String path) {
        return ((YamlConfiguration) file).getString(path);
    }

    @Override
    public Long getLong(Object file, String path) {
        return ((YamlConfiguration) file).getLong(path);
    }

    @Override
    public Integer getInteger(Object file, String path) {
        return ((YamlConfiguration) file).getInt(path);
    }

    @Override
    public List<String> getStringList(Object file, String path) {
        return ((YamlConfiguration) file).getStringList(path);
    }

    @Override
    public boolean getBoolean(Object file, String path, boolean def) {
        return ((YamlConfiguration) file).getBoolean(path, def);
    }

    @Override
    public String getString(Object file, String path, String def) {
        return ((YamlConfiguration) file).getString(path, def);
    }

    @Override
    public long getLong(Object file, String path, long def) {
        return ((YamlConfiguration) file).getLong(path, def);
    }

    @Override
    public int getInteger(Object file, String path, int def) {
        return ((YamlConfiguration) file).getInt(path, def);
    }

    @Override
    public boolean contains(Object file, String path) {
        return ((YamlConfiguration) file).contains(path);
    }

    @Override
    public String getFileName(Object file) {
        return ((YamlConfiguration) file).getName();
    }

    @Override
    public void callPunishmentEvent(Punishment punishment) {
        runSync(() -> Bukkit.getPluginManager().callEvent(new PunishmentEvent(punishment)));
    }

    @Override
    public void callRevokePunishmentEvent(Punishment punishment, boolean massClear) {
        runSync(() -> Bukkit.getPluginManager().callEvent(new RevokePunishmentEvent(punishment, massClear)));
    }

    @Override
    public boolean isOnlineMode() {
        return callSync(Bukkit::getOnlineMode);
    }

    @Override
    public boolean isFloodgatePlayer(UUID uuid) {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        return floodgate != null
                && FloodgateIdentity.isFloodgatePlayer(floodgate.getClass().getClassLoader(), uuid);
    }

    @Override
    public void notify(String perm, List<String> notification) {
        callSync(() -> {
            Bukkit.getOnlinePlayers()
                    .stream()
                    .filter(player -> player.hasPermission(perm))
                    .forEach(player -> notification.forEach(player::sendMessage));
            return null;
        });
    }

    @Override
    public void log(String msg) {
        Bukkit.getServer().getConsoleSender().sendMessage(msg.replaceAll("&", "§"));
    }

    @Override
    public boolean isUnitTesting() {
        return false;
    }

    @Override
    public boolean submitAuthorityCommand(Object sender, String command, String[] arguments) {
        PaperAgentClient client = agentClient;
        if (client == null) {
            return false;
        }
        AuthorityRequest.SenderKind kind = sender instanceof Player
                ? AuthorityRequest.SenderKind.PLAYER : AuthorityRequest.SenderKind.CONSOLE;
        String uuid = sender instanceof Player ? getInternUUID(sender) : "";
        List<String> values = new ArrayList<>();
        values.add(command);
        values.addAll(Arrays.asList(arguments));
        AuthorityRequestCodec.Result result = client.submitResult(new AuthorityRequest(UUID.randomUUID(),
                AuthorityRequest.Action.COMMAND, kind, uuid, getName(sender), values));
        boolean accepted = result != null && result.isSuccess();
        if (accepted && kind == AuthorityRequest.SenderKind.CONSOLE && result.getDetail() != null
                && !result.getDetail().isEmpty() && !"accepted".equals(result.getDetail())) {
            for (String line : result.getDetail().split("\\n", -1)) {
                sendMessage(sender, line);
            }
        }
        return accepted;
    }

    @Override
    public boolean submitAuthorityRequest(AuthorityRequest request) {
        PaperAgentClient client = agentClient;
        return client != null && !Bukkit.isPrimaryThread() && client.submit(request);
    }

    @Override
    public CompletableFuture<Boolean> submitAuthorityRequestAsync(AuthorityRequest request) {
        PaperAgentClient client = agentClient;
        if (client == null) {
            return CompletableFuture.completedFuture(false);
        }
        return client.submitAsync(request).thenApply(result -> result != null && result.isSuccess());
    }

    private List<String> getAuthorityCompletions(CommandSender sender, String command, String[] arguments) {
        PaperAgentClient client = agentClient;
        if (client == null || Universal.get().getRuntimeRole() != RuntimeRole.AGENT) {
            return Collections.emptyList();
        }
        String senderId = sender instanceof Player
                ? ((Player) sender).getUniqueId().toString().replace("-", "") : "console";
        String key = senderId + '\u0000' + command.toLowerCase() + '\u0000' + String.join("\u0000", arguments);
        long now = System.currentTimeMillis();
        CompletionEntry cached = authorityCompletions.get(key);
        if (cached != null && cached.expiresAt >= now) {
            return cached.values;
        }
        if (authorityCompletions.size() > 2048) {
            authorityCompletions.clear();
        }
        if (pendingCompletions.putIfAbsent(key, Boolean.TRUE) == null) {
            List<String> values = new ArrayList<>();
            values.add(command);
            values.addAll(Arrays.asList(arguments));
            AuthorityRequest.SenderKind kind = sender instanceof Player
                    ? AuthorityRequest.SenderKind.PLAYER : AuthorityRequest.SenderKind.CONSOLE;
            AuthorityRequest request = new AuthorityRequest(UUID.randomUUID(),
                    AuthorityRequest.Action.TAB_COMPLETE, kind,
                    sender instanceof Player ? senderId : "", sender.getName(), values);
            client.submitAsync(request).whenComplete((result, failure) -> {
                try {
                    if (failure == null && result != null && result.isSuccess()) {
                        List<String> suggestions = result.getDetail().isEmpty()
                                ? Collections.emptyList()
                                : Arrays.asList(result.getDetail().split("\u001f", -1));
                        authorityCompletions.put(key, new CompletionEntry(
                                Collections.unmodifiableList(new ArrayList<>(suggestions)),
                                System.currentTimeMillis() + 5_000L));
                    }
                } finally {
                    pendingCompletions.remove(key);
                }
            });
        }
        return Collections.emptyList();
    }

    private static final class CompletionEntry {
        private final List<String> values;
        private final long expiresAt;

        private CompletionEntry(List<String> values, long expiresAt) {
            this.values = values;
            this.expiresAt = expiresAt;
        }
    }

    private <T> T callSync(Supplier<T> action) {
        if (Bukkit.isPrimaryThread()) {
            return action.get();
        }

        FutureTask<T> task = new FutureTask<>(action::get);
        Bukkit.getScheduler().runTask(getPlugin(), task);
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Bukkit main thread", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Bukkit main-thread operation failed", exception.getCause());
        }
    }
}
