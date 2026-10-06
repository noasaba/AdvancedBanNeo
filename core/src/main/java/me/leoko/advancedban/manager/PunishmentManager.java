package me.leoko.advancedban.manager;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.utils.InterimData;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.SQLQuery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Punishment Manager handles the punishments. It loads and parses them from the database, caches them
 * and eventually discards them again.
 */
public class PunishmentManager {

    private static PunishmentManager instance = null;
    private volatile Set<Punishment> punishments = ConcurrentHashMap.newKeySet();
    private volatile Set<Punishment> history = ConcurrentHashMap.newKeySet();
    private final Set<String> cached = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshingOnlinePlayers = new AtomicBoolean();
    private final Object cacheRefreshLock = new Object();
    private volatile boolean agentSnapshotReady;
    private volatile boolean agentSnapshotPreviouslyReady;
    
    private Universal universal() {
    	return Universal.get();
    }

    /**
     * Get the punishment manager.
     *
     * @return the punishment manager instance
     */
    public static synchronized PunishmentManager get() {
        return instance == null ? instance = new PunishmentManager() : instance;
    }

    /**
     * Initially clears out all expired punishments.
     */
    public void setup() {
        DatabaseManager.get().executeStatement(SQLQuery.DELETE_OLD_PUNISHMENTS, TimeManager.getTime());
        if (!universal().getMethods().isUnitTesting()) {
            universal().getMethods().scheduleAsyncRep(this::cleanupExpired, 1200L, 1200L);
            long syncSeconds = universal().getMethods().getLong(
                    universal().getMethods().getConfig(), "MySQLCacheSyncInterval", 0L);
            if (DatabaseManager.get().isUseMySQL() && syncSeconds > 0L) {
                long maximumTicks = Long.MAX_VALUE / 50L;
                long syncTicks = syncSeconds > maximumTicks / 20L ? maximumTicks : syncSeconds * 20L;
                universal().getMethods().scheduleAsyncRep(this::refreshOnlinePlayers, syncTicks, syncTicks);
            }
        }
        // Seems useless as the Interim Data which get's loaded just is ignored
//        for (Object player : mi.getOnlinePlayers()) {
//            String name = mi.getName(player).toLowerCase();
//            load(name, UUIDManager.get().getUUID(name), mi.getIP(player));
//        }
    }

    /**
     * Get a users punishments as {@link InterimData}. This method is meant to be called if the goal eventually is
     * to add the users punishments to the cache. If you are just interested in the specific punishments there are
     * more convenient methods as {@link #getBan(String)}, {@link #getMute(String)}, {@link #getWarns(String)}
     * or {@link #getPunishments(String, PunishmentType, boolean)}.
     *
     * @param name the users name
     * @param uuid the users uuid
     * @param ip   the users ip
     * @return the interim data
     */
    public InterimData load(String name, String uuid, String ip) {
        String legacyNameUuid = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
        boolean hasLegacyNameUuid = legacyNameUuid != null && !legacyNameUuid.isEmpty()
                && !matches(legacyNameUuid, uuid);
        if (universal().getRuntimeRole().isAgent()) {
            // A disconnected Agent keeps enforcing the last complete active snapshot.
            // Before the first complete snapshot, there is no trustworthy local state,
            // so retain the existing lockdown behavior.
            if (!agentSnapshotReady && !agentSnapshotPreviouslyReady) {
                return null;
            }
            Set<Punishment> current = new HashSet<>();
            for (Punishment punishment : punishments) {
                if ((matches(punishment.getUuid(), uuid) || matches(punishment.getUuid(), ip)
                        || hasLegacyNameUuid && matches(punishment.getUuid(), legacyNameUuid))
                        && !punishment.isExpired()) {
                    current.add(punishment);
                }
            }
            Set<Punishment> previous = new HashSet<>();
            for (Punishment punishment : history) {
                if (matches(punishment.getUuid(), uuid) || matches(punishment.getUuid(), ip)
                        || hasLegacyNameUuid && matches(punishment.getUuid(), legacyNameUuid)) {
                    previous.add(punishment);
                }
            }
            return new InterimData(uuid, name, ip, current, previous);
        }
        Set<Punishment> punishments = new HashSet<>();
        Set<Punishment> history = new HashSet<>();
        try {
            if (!loadPunishmentRows(uuid, ip, punishments, history)) {
                return null;
            }
            // Before verified platform UUIDs were available, offline Paper stored the
            // lowercase player name in the UUID column. Keep those existing records
            // enforceable after Floodgate supplies its distinct XUID-derived UUID.
            if (hasLegacyNameUuid && !loadPunishmentRows(legacyNameUuid, legacyNameUuid,
                    punishments, history)) {
                return null;
            }
        } catch (SQLException ex) {
            Universal universal = universal();
            universal.log("An error has occurred loading the punishments from the database.");
            universal.debugSqlException(ex);
            return null;
        }
        return new InterimData(uuid, name, ip, punishments, history);
    }

    private boolean loadPunishmentRows(String uuid, String ip, Set<Punishment> punishments,
                                       Set<Punishment> history) throws SQLException {
        try (ResultSet resultsPunishments = DatabaseManager.get().executeResultStatement(
                SQLQuery.SELECT_USER_PUNISHMENTS_WITH_IP, uuid, ip);
             ResultSet resultsHistory = DatabaseManager.get().executeResultStatement(
                     SQLQuery.SELECT_USER_PUNISHMENTS_HISTORY_WITH_IP, uuid, ip)) {
            if (resultsHistory == null || resultsPunishments == null) {
                return false;
            }
            while (resultsPunishments.next()) {
                punishments.add(getPunishmentFromResultSet(resultsPunishments));
            }
            while (resultsHistory.next()) {
                history.add(getPunishmentFromResultSet(resultsHistory));
            }
            return true;
        }
    }

    private Set<Punishment> loadCurrent(String uuid, String ip) {
        Set<Punishment> current = new HashSet<>();
        try (ResultSet results = DatabaseManager.get().executeResultStatement(
                SQLQuery.SELECT_USER_PUNISHMENTS_WITH_IP, uuid, ip)) {
            if (results == null) {
                return null;
            }
            while (results.next()) {
                current.add(getPunishmentFromResultSet(results));
            }
            return current;
        } catch (SQLException exception) {
            universal().log("An error has occurred refreshing current punishments.");
            universal().debugSqlException(exception);
            return null;
        }
    }

    /**
     * Discard a players punishments from the cache.
     *
     * @param name the name
     */
    public void discard(String name) {
        if (name == null) {
            return;
        }
        name = name.toLowerCase();
        String ip = Universal.get().getIps().get(name);
        String uuid = UUIDManager.get().getUUID(name);
        invalidate(name, uuid, ip);
    }

    /**
     * Invalidates local punishment data without performing a UUID lookup.
     * Network transports use the already resolved UUID/IP key.
     */
    public void invalidate(String name, String uuid, String ip) {
        synchronized (cacheRefreshLock) {
            invalidateLocked(name, uuid, ip);
        }
    }

    private void invalidateLocked(String name, String uuid, String ip) {
        name = name == null ? null : name.toLowerCase();
        if (name != null) {
            cached.remove(name);
        }
        if (uuid != null) {
            cached.remove(uuid);
        }
        if (ip != null) {
            cached.remove(ip);
        }

        Iterator<Punishment> iterator = punishments.iterator();
        while (iterator.hasNext()) {
            Punishment punishment = iterator.next();
            if (matches(punishment.getUuid(), uuid) || matches(punishment.getUuid(), ip)) {
                iterator.remove();
            }
        }

        iterator = history.iterator();
        while (iterator.hasNext()) {
            Punishment punishment = iterator.next();
            if (matches(punishment.getUuid(), uuid) || matches(punishment.getUuid(), ip)) {
                iterator.remove();
            }
        }
    }

    /** Replaces a cached target only after a fresh database load succeeds. */
    public boolean reload(String name, String uuid, String ip) {
        synchronized (cacheRefreshLock) {
            InterimData refreshed = load(name, uuid, ip);
            if (refreshed == null) {
                return false;
            }
            invalidateLocked(name, uuid, ip);
            refreshed.accept();
            return true;
        }
    }

    private static boolean matches(String actual, String expected) {
        return expected != null && expected.equals(actual);
    }

    /** Removes every active cache instance for one persisted punishment row. */
    public void removeLoadedPunishment(int id) {
        synchronized (cacheRefreshLock) {
            punishments.removeIf(punishment -> punishment.getId() == id);
        }
    }

    /** Adds a persisted punishment to the local caches without racing a refresh. */
    public void addLoadedPunishment(Punishment punishment, boolean current) {
        synchronized (cacheRefreshLock) {
            history.add(punishment);
            if (current) {
                punishments.add(punishment);
            }
        }
    }

    /**
     * Get all punishments which belong to the given uuid or ip.
     *
     * @param target  the uuid or ip to search for
     * @param put     the basic punishment type to search for ({@link PunishmentType#BAN} would also include Tempbans).
     *                Use <code>null</code> to search for all punishments.
     * @param current if only active punishments should be included.
     * @return the punishments
     */
    public List<Punishment> getPunishments(String target, PunishmentType put, boolean current) {
        List<Punishment> ptList = new ArrayList<>();

        if (universal().getRuntimeRole().isAgent()) {
            if (!agentSnapshotReady || target == null) {
                return ptList;
            }
            for (Punishment punishment : current ? punishments : history) {
                if (target.equals(punishment.getUuid())
                        && (put == null || put == punishment.getType().getBasic())
                        && (!current || !punishment.isExpired())) {
                    ptList.add(punishment);
                }
            }
            return ptList;
        }

        if (isCached(target)) {
            for (Iterator<Punishment> iterator = (current ? punishments : history).iterator(); iterator.hasNext(); ) {
                Punishment pt = iterator.next();
                if ((put == null || put == pt.getType().getBasic()) && pt.getUuid().equals(target)) {
                    if (!current || !pt.isExpired()) {
                        ptList.add(pt);
                    } else {
                        if (pt.deleteChecked(null, false, false)) {
                            iterator.remove();
                        }
                    }
                }
            }
        } else {
            try (ResultSet rs = DatabaseManager.get().executeResultStatement(current ? SQLQuery.SELECT_USER_PUNISHMENTS : SQLQuery.SELECT_USER_PUNISHMENTS_HISTORY, target)) {
                if (rs == null) {
                    return ptList;
                }
                while (rs.next()) {
                    Punishment punishment = getPunishmentFromResultSet(rs);
                    if ((put == null || put == punishment.getType().getBasic()) && (!current || !punishment.isExpired())) {
                        ptList.add(punishment);
                    } else if (current && punishment.isExpired()) {
                        punishment.deleteChecked(null, false, false);
                    }
                }
            } catch (SQLException ex) {
            	Universal universal = universal();
                universal.log("An error has occurred getting the punishments for " + target);
                universal.debugSqlException(ex);
            }
        }
        return ptList;
    }

    /**
     * Get parsed punishments from the database queried by the given {@link SQLQuery}.<br>
     * The parameters work as described in {@link MessageManager#sendMessage(Object, String, boolean, String...)}.
     *
     * @param sqlQuery   the sql query
     * @param parameters the parameters
     * @return the punishments
     */
    public List<Punishment> getPunishments(SQLQuery sqlQuery, Object... parameters) {
        List<Punishment> ptList = new ArrayList<>();

        if (universal().getRuntimeRole().isAgent()) {
            if (!agentSnapshotReady) {
                return ptList;
            }
            Collection<Punishment> source;
            switch (sqlQuery) {
                case SELECT_ALL_PUNISHMENTS:
                case SELECT_ALL_PUNISHMENTS_LIMIT:
                case SELECT_USER_PUNISHMENTS:
                case SELECT_USER_PUNISHMENTS_WITH_IP:
                case SELECT_EXACT_PUNISHMENT:
                case SELECT_PUNISHMENT_BY_ID:
                    source = punishments;
                    break;
                case SELECT_ALL_PUNISHMENTS_HISTORY:
                case SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT:
                case SELECT_USER_PUNISHMENTS_HISTORY:
                case SELECT_USER_PUNISHMENTS_HISTORY_WITH_IP:
                case SELECT_USER_PUNISHMENTS_HISTORY_BY_CALCULATION:
                    source = history;
                    break;
                default:
                    // Writes and schema statements are deliberately not
                    // emulated on a DB-less Agent.
                    return ptList;
            }
            for (Punishment punishment : source) {
                if (matchesAgentQuery(punishment, sqlQuery, parameters)) {
                    ptList.add(punishment);
                }
            }
            ptList.sort(Comparator.comparingLong(Punishment::getStart).reversed());
            if ((sqlQuery == SQLQuery.SELECT_ALL_PUNISHMENTS_LIMIT
                    || sqlQuery == SQLQuery.SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT)
                    && parameters.length > 0 && parameters[0] instanceof Number) {
                int limit = Math.max(0, ((Number) parameters[0]).intValue());
                if (ptList.size() > limit) {
                    return new ArrayList<>(ptList.subList(0, limit));
                }
            }
            return ptList;
        }

        ResultSet rs = DatabaseManager.get().executeResultStatement(sqlQuery, parameters);
        if (rs == null) {
            return ptList;
        }
        try {
            while (rs.next()) {
                Punishment punishment = getPunishmentFromResultSet(rs);
                ptList.add(punishment);
            }
            rs.close();
        } catch (SQLException ex) {
        	Universal universal = universal();
            universal.log("An error has occurred executing a query in the database.");
            universal.debug("Query: \n" + sqlQuery);
            universal.debugSqlException(ex);
        }
        return ptList;
    }

    private static boolean matchesAgentQuery(Punishment punishment, SQLQuery query, Object[] parameters) {
        switch (query) {
            case SELECT_USER_PUNISHMENTS:
            case SELECT_USER_PUNISHMENTS_HISTORY:
                return parameters.length >= 1 && Objects.equals(punishment.getUuid(), String.valueOf(parameters[0]));
            case SELECT_USER_PUNISHMENTS_WITH_IP:
            case SELECT_USER_PUNISHMENTS_HISTORY_WITH_IP:
                return parameters.length >= 2 && (Objects.equals(punishment.getUuid(), String.valueOf(parameters[0]))
                        || Objects.equals(punishment.getUuid(), String.valueOf(parameters[1])));
            case SELECT_USER_PUNISHMENTS_HISTORY_BY_CALCULATION:
                return parameters.length >= 2 && Objects.equals(punishment.getUuid(), String.valueOf(parameters[0]))
                        && parameters[1] != null && punishment.getCalculation() != null
                        && punishment.getCalculation().equalsIgnoreCase(String.valueOf(parameters[1]));
            case SELECT_EXACT_PUNISHMENT:
                return parameters.length >= 3 && Objects.equals(punishment.getUuid(), String.valueOf(parameters[0]))
                        && punishment.getStart() == ((Number) parameters[1]).longValue()
                        && punishment.getType().name().equals(String.valueOf(parameters[2]));
            case SELECT_PUNISHMENT_BY_ID:
                return parameters.length >= 1 && punishment.getId() == ((Number) parameters[0]).intValue();
            default:
                return true;
        }
    }

    /**
     * Get an active punishment by id.
     *
     * @param id the id
     * @return the punishment
     */
    public Punishment getPunishment(int id) {
        if (universal().getRuntimeRole().isAgent()) {
            return getLoadedPunishments(false).stream()
                    .filter(punishment -> punishment.getId() == id && !punishment.isExpired())
                    .findAny().orElse(null);
        }
        final Optional<Punishment> cachedPunishment = getLoadedPunishments(false).stream()
                .filter(punishment -> punishment.getId() == id).findAny();

        if (cachedPunishment.isPresent()) {
            Punishment punishment = cachedPunishment.get();
            if (!punishment.isExpired()) {
                return punishment;
            }
            punishment.deleteChecked(null, false, true);
            return null;
        }


        try (ResultSet rs = DatabaseManager.get().executeResultStatement(SQLQuery.SELECT_PUNISHMENT_BY_ID, id)) {
            if (rs == null) {
                return null;
            }
            if (rs.next()) {
                final Punishment punishment = getPunishmentFromResultSet(rs);
                if (!punishment.isExpired())
                    return punishment;
            }
        } catch (SQLException ex) {
        	Universal universal = universal();
            universal.log("An error has occurred getting a punishment by his id.");
            universal.debug("Punishment id: '" + id + "'");
            universal.debugSqlException(ex);
        }

        return null;
    }

    /**
     * Get an active warning by id.
     *
     * @param id the id
     * @return the warning
     */
    public Punishment getWarn(int id) {
        Punishment punishment = getPunishment(id);

        if (punishment == null)
            return null;

        return punishment.getType().getBasic() == PunishmentType.WARNING ? punishment : null;
    }

    /**
     * Get a players active warnings.
     *
     * @param uuid the players uuid
     * @return the warns
     */
    public List<Punishment> getWarns(String uuid) {
        return getPunishments(uuid, PunishmentType.WARNING, true);
    } /**
     * Get an active note by id.
    *
    * @param id the id
    * @return the note
    */
   public Punishment getNote(int id) {
       Punishment punishment = getPunishment(id);

       if (punishment == null)
           return null;

       return punishment.getType().getBasic() == PunishmentType.NOTE ? punishment : null;
   }

   /**
    * Get a players active note.
    *
    * @param uuid the players uuid
    * @return the note
    */
   public List<Punishment> getNotes(String uuid) {
       return getPunishments(uuid, PunishmentType.NOTE, true);
   }

    /**
     * Get a players active ban.
     *
     * @param uuid the players uuid (can also be an IP)
     * @return the ban or <code>null</code> if not banned
     */
    public Punishment getBan(String uuid) {
        List<Punishment> punishments = getPunishments(uuid, PunishmentType.BAN, true);
        return punishments.isEmpty() ? null : punishments.get(0);
    }

    /**
     * Get a players active mute.
     *
     * @param uuid the players uuid
     * @return the mute or <code>null</code> if not muted
     */
    public Punishment getMute(String uuid) {
        List<Punishment> punishments = getPunishments(uuid, PunishmentType.MUTE, true);
        return punishments.isEmpty() ? null : punishments.get(0);
    }

    /**
     * Check whether a player is banned.
     *
     * @param uuid the players uuid (can also be an IP)
     * @return whether the player is banned
     */
    public boolean isBanned(String uuid) {
        return getBan(uuid) != null;
    }

    /**
     * Check whether a player is muted.
     *
     * @param uuid the players uuid
     * @return whether the player is muted
     */
    public boolean isMuted(String uuid) {
        return getMute(uuid) != null;
    }

    /**
     * Performs a side-effect-free, in-memory mute lookup suitable for chat
     * pipelines whose callback thread is not controlled by AdvancedBan.
     */
    public Punishment getRuntimeMute(String uuid) {
        return getRuntimePunishment(uuid, PunishmentType.MUTE);
    }

    public Punishment getRuntimeBan(String uuidOrIp) {
        return getRuntimePunishment(uuidOrIp, PunishmentType.BAN);
    }

    private Punishment getRuntimePunishment(String target, PunishmentType basicType) {
        if (target == null) {
            return null;
        }
        String normalized = target.replace("-", "");
        for (Punishment punishment : punishments) {
            if (normalized.equalsIgnoreCase(punishment.getUuid())
                    && punishment.getType().getBasic() == basicType
                    && !punishment.isExpired()) {
                return punishment;
            }
        }
        return null;
    }

    /** Atomically installs a full Authority snapshot on an Agent. */
    public void replaceAgentSnapshot(Collection<Punishment> snapshot) {
        replaceAgentSnapshot(snapshot, true);
    }

    /**
     * Installs active state while keeping the Agent unavailable until the
     * matching history snapshot has also been installed.
     */
    public void beginAgentSnapshotSynchronization(Collection<Punishment> snapshot) {
        replaceAgentSnapshot(snapshot, false);
    }

    private void replaceAgentSnapshot(Collection<Punishment> snapshot, boolean ready) {
        if (!universal().getRuntimeRole().isAgent()) {
            throw new IllegalStateException("Only Agent nodes accept Authority snapshots");
        }
        Set<Punishment> replacement = ConcurrentHashMap.newKeySet();
        Set<Punishment> replacementHistory = ConcurrentHashMap.newKeySet();
        if (snapshot != null) {
            for (Punishment punishment : snapshot) {
                if (punishment != null && !punishment.isExpired()) {
                    replacement.add(punishment);
                }
            }
        }
        synchronized (cacheRefreshLock) {
            punishments = replacement;
            history = replacementHistory;
            cached.clear();
            agentSnapshotReady = ready;
            if (ready) {
                agentSnapshotPreviouslyReady = true;
            }
        }
    }

    /** Atomically installs the Authority's complete public history view. */
    public void replaceAgentHistorySnapshot(Collection<Punishment> snapshot) {
        if (!universal().getRuntimeRole().isAgent()) {
            throw new IllegalStateException("Only Agent nodes accept Authority history snapshots");
        }
        Set<Punishment> replacement = ConcurrentHashMap.newKeySet();
        if (snapshot != null) {
            replacement.addAll(snapshot);
        }
        synchronized (cacheRefreshLock) {
            history = replacement;
        }
    }

    /** Publishes the active and history snapshots as one complete Authority view. */
    public void completeAgentSnapshotSynchronization() {
        if (!universal().getRuntimeRole().isAgent()) {
            throw new IllegalStateException("Only Agent nodes complete Authority snapshots");
        }
        synchronized (cacheRefreshLock) {
            agentSnapshotReady = true;
            agentSnapshotPreviouslyReady = true;
        }
    }

    /** Adds an Authority-created immutable history row idempotently. */
    public void appendAgentHistoryPunishment(Punishment punishment) {
        if (!universal().getRuntimeRole().isAgent() || punishment == null) {
            return;
        }
        synchronized (cacheRefreshLock) {
            Set<Punishment> next = concurrentCopy(history);
            next.removeIf(existing -> existing.getId() == punishment.getId());
            next.add(punishment);
            history = next;
        }
    }

    /** Applies one idempotent Authority update without triggering DB/events. */
    public void applyAgentPunishment(Punishment punishment) {
        if (!universal().getRuntimeRole().isAgent() || punishment == null) {
            return;
        }
        synchronized (cacheRefreshLock) {
            Set<Punishment> next = concurrentCopy(punishments);
            next.removeIf(existing -> existing.getId() == punishment.getId());
            if (!punishment.isExpired()) {
                next.add(punishment);
            }
            punishments = next;
        }
    }

    /** Applies one idempotent Authority revoke without triggering DB/events. */
    public void revokeAgentPunishment(int id) {
        if (!universal().getRuntimeRole().isAgent()) {
            return;
        }
        synchronized (cacheRefreshLock) {
            Set<Punishment> next = concurrentCopy(punishments);
            next.removeIf(existing -> existing.getId() == id);
            punishments = next;
        }
    }

    private static Set<Punishment> concurrentCopy(Collection<Punishment> source) {
        Set<Punishment> copy = ConcurrentHashMap.newKeySet();
        copy.addAll(source);
        return copy;
    }

    public boolean isAgentSnapshotReady() {
        return agentSnapshotReady;
    }

    public void markAgentSnapshotUnavailable() {
        if (universal().getRuntimeRole().isAgent()) {
            synchronized (cacheRefreshLock) {
                agentSnapshotReady = false;
                // Active punishments remain for fail-closed enforcement, but
                // history must not be exposed as fresh after a disconnect.
                history = ConcurrentHashMap.newKeySet();
            }
        }
    }

    /**
     * Check whether the data for the given uuid, ip or username are currently cached.
     *
     * @param target the target (uuid, ip or username)
     * @return whether the targets data is cached
     */
    public boolean isCached(String target) {
        return cached.contains(target);
    }

    /**
     * Mark the InterimData as cached.
     * This method des not acually cache the data, see {@link InterimData#accept()} to do that.
     *
     * @param data the data
     */
    public void setCached(InterimData data) {
        cached.add(data.getName());
        if (data.getIp() != null) {
            cached.add(data.getIp());
        }
        if (data.getUuid() != null) {
            cached.add(data.getUuid());
        }
    }

    /**
     * Get punishment-time calculation level.
     * This level is represented by the amount a user has been punished using the given time-layout.
     *
     * @param uuid   the players uuid
     * @param layout the time-layout name
     * @return the calculation level
     */
    public int getCalculationLevel(String uuid, String layout) {
        if (universal().getRuntimeRole().isAgent()) {
            if (!agentSnapshotReady || uuid == null || layout == null) {
                return 0;
            }
            return (int) history.stream().filter(pt -> uuid.equals(pt.getUuid())
                    && layout.equalsIgnoreCase(pt.getCalculation())).count();
        }
        if (isCached(uuid)) {
            return (int) history.stream().filter(pt -> pt.getUuid().equals(uuid) && layout.equalsIgnoreCase(pt.getCalculation())).count();
        }

        int i = 0;
        try (ResultSet resultSet = DatabaseManager.get().executeResultStatement(SQLQuery.SELECT_USER_PUNISHMENTS_HISTORY_BY_CALCULATION, uuid, layout)) {
            if (resultSet == null) {
                return 0;
            }
            while (resultSet.next()) {
                i++;
            }

        } catch (SQLException ex) {
        	Universal universal = universal();
            universal.log("An error has occurred getting the level for the layout '" + layout + "' for '" + uuid + "'");
            universal.debugSqlException(ex);
        }
        return i;
    }

    /**
     * Get how many warnings a player has.
     *
     * @param uuid the players uuid
     * @return the current warning count
     */
    public int getCurrentWarns(String uuid) {
        return getWarns(uuid).size();
    }
    /**
     * Get how many notes a player has.
     *
     * @param uuid the players uuid
     * @return the current note count
     */
    public int getCurrentNotes(String uuid) {
        return getNotes(uuid).size();
    }

    /**
     * Get all cached punishments.
     *
     * @param checkExpired whether to look for and remove expired punishments
     * @return the cached punishments
     */
    public Set<Punishment> getLoadedPunishments(boolean checkExpired) {
        if (checkExpired && universal().getRuntimeRole().isAuthority()) {
            List<Punishment> toDelete = new ArrayList<>();
            for (Punishment pu : punishments) {
                if (pu.isExpired()) {
                    toDelete.add(pu);
                }
            }
            for (Punishment pu : toDelete) {
                pu.delete();
            }
        }
        return punishments;
    }

    /** Removes cached punishments after their configured end time. */
    public void cleanupExpired() {
        for (Punishment punishment : new ArrayList<>(punishments)) {
            if (punishment.isExpired()) {
                punishment.deleteChecked(null, false, true);
            }
        }
    }

    /**
     * Refreshes online-player caches from shared MySQL. This is disabled by
     * default and is intended for mixed or multi-proxy networks without a
     * common pub/sub transport.
     */
    public void refreshOnlinePlayers() {
        if (!refreshingOnlinePlayers.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Object player : universal().getMethods().getOnlinePlayers()) {
                try {
                    String name = universal().getMethods().getName(player);
                    if (name == null) {
                        continue;
                    }
                    String uuid = UUIDManager.get().getUUID(name.toLowerCase());
                    if (uuid == null) {
                        continue;
                    }
                    String ip = universal().getMethods().getIP(player);
                    Punishment ban;
                    synchronized (cacheRefreshLock) {
                        Set<Punishment> refreshed = loadCurrent(uuid, ip);
                        if (refreshed == null) {
                            continue;
                        }
                        punishments.removeIf(punishment -> matches(punishment.getUuid(), uuid)
                                || matches(punishment.getUuid(), ip));
                        punishments.addAll(refreshed);
                        ban = refreshed.stream()
                                .filter(punishment -> punishment.getType().getBasic() == PunishmentType.BAN
                                        && !punishment.isExpired())
                                .findFirst().orElse(null);
                    }
                    if (ban != null) {
                        universal().getMethods().kickPlayer(name, ban.getLayoutBSN());
                    }
                } catch (RuntimeException exception) {
                    universal().log("Failed to refresh an online punishment cache.");
                    universal().debugException(exception);
                }
            }
        } finally {
            refreshingOnlinePlayers.set(false);
        }
    }

    /**
     * Get a Punishment from a {@link ResultSet}
     *
     * @param rs the result set
     * @return the punishment from the result set
     * @throws SQLException the sql exception
     */
    public Punishment getPunishmentFromResultSet(ResultSet rs) throws SQLException {
        return new Punishment(
                rs.getString("name"),
                rs.getString("uuid"), rs.getString("reason"),
                rs.getString("operator"),
                PunishmentType.valueOf(rs.getString("punishmentType")),
                rs.getLong("start"),
                rs.getLong("end"),
                rs.getString("calculation"),
                rs.getInt("id"));
    }

    /**
     * Get all cached history punishments.
     *
     * @return the loaded history
     */
    public Set<Punishment> getLoadedHistory() {
        return history;
    }


//    public long getCalculation(String layout, String name, String uuid) {
//        long end = TimeManager.getTime();
//        MethodInterface mi = Universal.get().getMethods();
//
//        int i = getCalculationLevel(name, uuid);
//
//        List<String> timeLayout = mi.getStringList(mi.getLayouts(), "Time." + layout);
//        String time = timeLayout.get(timeLayout.size() <= i ? timeLayout.size() - 1 : i);
//        long toAdd = TimeManager.toMilliSec(time.toLowerCase());
//        end += toAdd;
//
//        return end;
//    }
}
