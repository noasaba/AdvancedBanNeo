package me.leoko.advancedban.manager;

import com.zaxxer.hikari.HikariDataSource;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.utils.DynamicDataSource;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.SQLQuery;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.RowSetProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;

/**
 * The Database Manager is used to interact directly with the database is use.<br>
 * Will automatically direct the requests to either MySQL or HSQLDB.
 * <br><br>
 * Looking to request {@link me.leoko.advancedban.utils.Punishment Punishments} from the Database?
 * Use {@link PunishmentManager#getPunishments(SQLQuery, Object...)} or
 * {@link PunishmentManager#getPunishmentFromResultSet(ResultSet)} for already parsed data.
 */
public class DatabaseManager {

    private HikariDataSource dataSource;
    private boolean useMySQL;

    private RowSetFactory factory;
    private final Object[] punishmentCreationLocks = new Object[64];
    
    private static DatabaseManager instance = null;

    public DatabaseManager() {
        for (int i = 0; i < punishmentCreationLocks.length; i++) {
            punishmentCreationLocks[i] = new Object();
        }
    }

    /**
     * Get the instance of the command manager
     *
     * @return the database manager instance
     */
    public static synchronized DatabaseManager get() {
        return instance == null ? instance = new DatabaseManager() : instance;
    }

    /**
     * Initially connects to the database and sets up the required tables of they don't already exist.
     *
     * @param useMySQLServer whether to preferably use MySQL (uses HSQLDB as fallback)
     */
    public void setup(boolean useMySQLServer) {
        useMySQL = useMySQLServer;

        try {
            dataSource = new DynamicDataSource(useMySQL).generateDataSource();
        } catch (ClassNotFoundException ex) {
            Universal.get().log("§cERROR: Failed to configure data source!");
            Universal.get().debug(ex.getMessage());
            return;
        }

        executeStatement(SQLQuery.CREATE_TABLE_PUNISHMENT);
        executeStatement(SQLQuery.CREATE_TABLE_PUNISHMENT_HISTORY);
    }

    /**
     * Shuts down the HSQLDB if used.
     */
    public synchronized void shutdown() {
        HikariDataSource activeDataSource = dataSource;
        if (activeDataSource == null) {
            return;
        }
        dataSource = null;

        if (!useMySQL) {
            try(Connection connection = activeDataSource.getConnection(); final PreparedStatement statement = connection.prepareStatement("SHUTDOWN")){
                statement.execute();
            }catch (SQLException exc){
                Universal.get().log("An unexpected error has occurred turning off the database");
                Universal.get().debugException(exc);
            }
        }

        activeDataSource.close();
    }
    
    private CachedRowSet createCachedRowSet() throws SQLException {
    	if (factory == null) {
    		factory = RowSetProvider.newFactory();
    	}
    	return factory.createCachedRowSet();
    }

    /**
     * Execute a sql statement without any results.
     *
     * @param sql        the sql statement
     * @param parameters the parameters
     */
    public void executeStatement(SQLQuery sql, Object... parameters) {
        executeStatement(sql, false, parameters);
    }

    /**
     * Executes an update and reports whether it reached the database.
     * The legacy void method remains available for API compatibility.
     */
    public synchronized boolean executeStatementChecked(SQLQuery sql, Object... parameters) {
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            setParameters(statement, parameters);
            return statement.executeUpdate() > 0;
        } catch (SQLException | RuntimeException exception) {
            Universal.get().log("An unexpected error has occurred updating the database");
            Universal.get().debugException(exception);
            return false;
        }
    }

    /**
     * Execute a sql statement.
     *
     * @param sql        the sql statement
     * @param parameters the parameters
     * @return the result set
     */
    public ResultSet executeResultStatement(SQLQuery sql, Object... parameters) {
        return executeStatement(sql, true, parameters);
    }

    /**
     * Persists a punishment and its history entry in one transaction.
     *
     * @return the active punishment id, {@code -1} for a successfully stored
     *         kick, or {@code null} when nothing was committed
     */
    public synchronized Integer createPunishment(boolean kick, Object... parameters) {
        if (dataSource == null) {
            return null;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int punishmentId = persistPunishment(connection, kick, parameters);
                connection.commit();
                return punishmentId;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        } catch (SQLException | RuntimeException exception) {
            Universal.get().log("An unexpected error has occurred saving a punishment in the database");
            Universal.get().debugException(exception);
            return null;
        }
    }

    /**
     * Checks and creates a command-level BAN/MUTE using one database connection.
     * MySQL advisory locks extend the operation to other upgraded proxy
     * instances without changing the 2.3.0 schema.
     */
    public PunishmentCreationResult createPunishmentIfAbsent(boolean kick, String target,
                                                              PunishmentType type, long now,
                                                              Object... parameters) {
        HikariDataSource activeDataSource = dataSource;
        if (activeDataSource == null || target == null || type == null) {
            return PunishmentCreationResult.failed();
        }
        String lockName = "advancedban:" + type.getBasic().name() + ':' + target;
        Object localLock = punishmentCreationLocks[(lockName.hashCode() & Integer.MAX_VALUE)
                % punishmentCreationLocks.length];
        synchronized (localLock) {
            Connection connection = null;
            boolean mysqlLockAcquired = false;
            try {
                connection = activeDataSource.getConnection();
                if (useMySQL) {
                    try (PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?, 10)")) {
                        acquire.setString(1, lockName);
                        try (ResultSet result = acquire.executeQuery()) {
                            if (!result.next() || result.getInt(1) != 1) {
                                Universal.get().log("Timed out waiting for the punishment creation lock.");
                                return PunishmentCreationResult.failed();
                            }
                            mysqlLockAcquired = true;
                        }
                    }
                }

                connection.setAutoCommit(false);
                try {
                    if (hasActivePunishment(connection, target, type.getBasic(), now)) {
                        connection.rollback();
                        return PunishmentCreationResult.alreadyActive();
                    }
                    int punishmentId = persistPunishment(connection, kick, parameters);
                    connection.commit();
                    return PunishmentCreationResult.created(punishmentId);
                } catch (SQLException | RuntimeException exception) {
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException | RuntimeException exception) {
                Universal.get().log("An unexpected error has occurred creating a locked punishment");
                Universal.get().debugException(exception);
                return PunishmentCreationResult.failed();
            } finally {
                if (connection != null) {
                    if (mysqlLockAcquired && !releaseMysqlLock(connection, lockName)) {
                        try {
                            activeDataSource.evictConnection(connection);
                        } catch (RuntimeException exception) {
                            Universal.get().debugException(exception);
                        }
                    }
                    try {
                        connection.close();
                    } catch (SQLException exception) {
                        Universal.get().debugSqlException(exception);
                    }
                }
            }
        }
    }

    private int persistPunishment(Connection connection, boolean kick, Object... parameters) throws SQLException {
        try (PreparedStatement history = connection.prepareStatement(SQLQuery.INSERT_PUNISHMENT_HISTORY.toString())) {
            setParameters(history, parameters);
            history.executeUpdate();
        }

        int punishmentId = -1;
        if (!kick) {
            try (PreparedStatement current = connection.prepareStatement(
                    SQLQuery.INSERT_PUNISHMENT.toString(), Statement.RETURN_GENERATED_KEYS)) {
                setParameters(current, parameters);
                current.executeUpdate();
                try (ResultSet keys = current.getGeneratedKeys()) {
                    if (keys.next()) {
                        punishmentId = keys.getInt(1);
                    }
                }
            }

            if (punishmentId == -1) {
                try (PreparedStatement select = connection.prepareStatement(SQLQuery.SELECT_EXACT_PUNISHMENT.toString())) {
                    select.setObject(1, parameters[1]);
                    select.setObject(2, parameters[5]);
                    select.setObject(3, parameters[4]);
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Inserted punishment could not be read back");
                        }
                        punishmentId = result.getInt("id");
                    }
                }
            }
        }
        return punishmentId;
    }

    private boolean hasActivePunishment(Connection connection, String target,
                                        PunishmentType type, long now) throws SQLException {
        String types = type == PunishmentType.BAN
                ? "('BAN','TEMP_BAN','IP_BAN','TEMP_IP_BAN')"
                : "('MUTE','TEMP_MUTE')";
        String table = useMySQL ? "`Punishments`" : "Punishments";
        String query = "SELECT id FROM " + table
                + " WHERE uuid = ? AND punishmentType IN " + types
                + " AND (end = -1 OR end > ?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, target);
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean releaseMysqlLock(Connection connection, String lockName) {
        try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            release.setString(1, lockName);
            try (ResultSet result = release.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        } catch (SQLException exception) {
            Universal.get().debugSqlException(exception);
            return false;
        }
    }

    private static void rollback(Connection connection, Exception exception) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            exception.addSuppressed(rollbackException);
        }
    }

    public static final class PunishmentCreationResult {
        public enum Status {
            CREATED,
            ALREADY_ACTIVE,
            FAILED
        }

        private final Status status;
        private final int id;

        private PunishmentCreationResult(Status status, int id) {
            this.status = status;
            this.id = id;
        }

        public static PunishmentCreationResult created(int id) {
            return new PunishmentCreationResult(Status.CREATED, id);
        }

        public static PunishmentCreationResult alreadyActive() {
            return new PunishmentCreationResult(Status.ALREADY_ACTIVE, -1);
        }

        public static PunishmentCreationResult failed() {
            return new PunishmentCreationResult(Status.FAILED, -1);
        }

        public Status getStatus() {
            return status;
        }

        public int getId() {
            return id;
        }
    }

    /**
     * Deletes a group of current punishments as one transaction.
     * No cache or event state is changed by this method.
     */
    public synchronized boolean deletePunishmentsAtomically(Collection<Integer> ids) {
        if (dataSource == null || ids == null || ids.isEmpty()) {
            return false;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(SQLQuery.DELETE_PUNISHMENT.toString())) {
                for (Integer id : ids) {
                    if (id == null) {
                        throw new SQLException("Punishment id must not be null");
                    }
                    statement.setInt(1, id);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Punishment " + id + " was not deleted");
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        } catch (SQLException | RuntimeException exception) {
            Universal.get().log("An unexpected error has occurred deleting punishments from the database");
            Universal.get().debugException(exception);
            return false;
        }
    }

    private ResultSet executeStatement(SQLQuery sql, boolean result, Object... parameters) {
        return executeStatement(sql.toString(), result, parameters);
    }

    private synchronized ResultSet executeStatement(String sql, boolean result, Object... parameters) {
    	try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {

    		for (int i = 0; i < parameters.length; i++) {
    			statement.setObject(i + 1, parameters[i]);
    		}

    		if (result) {
    			CachedRowSet results = createCachedRowSet();
    			results.populate(statement.executeQuery());
    			return results;
    		}
   			statement.execute();
    	} catch (SQLException ex) {
    		Universal.get().log(
   					"An unexpected error has occurred executing an Statement in the database\n"
   							+ "Please check the plugins/AdvancedBan/logs/latest.log file and report this "
    						+ "error in: https://github.com/DevLeoko/AdvancedBan/issues"
    				);
    		Universal.get().debug("Query: \n" + sql);
    		Universal.get().debugSqlException(ex);
       	} catch (NullPointerException ex) {
            Universal.get().log(
                    "An unexpected error has occurred connecting to the database\n"
                            + "Check if your MySQL data is correct and if your MySQL-Server is online\n"
                            + "Please check the plugins/AdvancedBan/logs/latest.log file and report this "
                            + "error in: https://github.com/DevLeoko/AdvancedBan/issues"
            );
            Universal.get().debugException(ex);
        }
        return null;
    }

    private static void setParameters(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    /**
     * Check whether there is a valid connection to the database.
     *
     * @return whether there is a valid connection
     */
    public boolean isConnectionValid() {
        return dataSource != null && dataSource.isRunning();
    }

    /**
     * Check whether MySQL is actually used.
     *
     * @return whether MySQL is used
     */
    public boolean isUseMySQL() {
        return useMySQL;
    }
}
