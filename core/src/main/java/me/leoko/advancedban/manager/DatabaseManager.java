package me.leoko.advancedban.manager;

import com.zaxxer.hikari.HikariDataSource;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.utils.DynamicDataSource;
import me.leoko.advancedban.utils.SQLQuery;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.RowSetProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
    
    private static DatabaseManager instance = null;

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
    public void shutdown() {
        if (dataSource == null) {
            return;
        }

        if (!useMySQL) {
            try(Connection connection = dataSource.getConnection(); final PreparedStatement statement = connection.prepareStatement("SHUTDOWN")){
                statement.execute();
            }catch (SQLException exc){
                Universal.get().log("An unexpected error has occurred turning off the database");
                Universal.get().debugException(exc);
            }
        }

        dataSource.close();
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
