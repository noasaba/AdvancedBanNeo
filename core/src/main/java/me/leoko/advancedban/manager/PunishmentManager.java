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
    private final Set<Punishment> punishments = ConcurrentHashMap.newKeySet();
    private final Set<Punishment> history = ConcurrentHashMap.newKeySet();
    private final Set<String> cached = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshingOnlinePlayers = new AtomicBoolean();
    private final Object cacheRefreshLock = new Object();
    
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
        Set<Punishment> punishments = new HashSet<>();
        Set<Punishment> history = new HashSet<>();
        try (ResultSet resultsPunishments = DatabaseManager.get().executeResultStatement(SQLQuery.SELECT_USER_PUNISHMENTS_WITH_IP, uuid, ip); ResultSet resultsHistory = DatabaseManager.get().executeResultStatement(SQLQuery.SELECT_USER_PUNISHMENTS_HISTORY_WITH_IP, uuid, ip)) {
            if (resultsHistory == null || resultsPunishments == null)
                return null;

            while (resultsPunishments.next()) {
                punishments.add(getPunishmentFromResultSet(resultsPunishments));
            }
            while (resultsHistory.next()) {
                history.add(getPunishmentFromResultSet(resultsHistory));
            }

        } catch (SQLException ex) {
        	Universal universal = universal();
            universal.log("An error has occurred loading the punishments from the database.");
            universal.debugSqlException(ex);
            return null;
        }
        return new InterimData(uuid, name, ip, punishments, history);
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

    /**
     * Get an active punishment by id.
     *
     * @param id the id
     * @return the punishment
     */
    public Punishment getPunishment(int id) {
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
        if (checkExpired) {
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
