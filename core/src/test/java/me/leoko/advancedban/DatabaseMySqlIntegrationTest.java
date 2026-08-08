package me.leoko.advancedban;

import com.zaxxer.hikari.HikariDataSource;
import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.TimeManager;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "ADVANCEDBAN_MYSQL_HOST", matches = ".+")
class DatabaseMySqlIntegrationTest {
    @TempDir
    static File dataFolder;

    private static DatabaseManager secondManager;

    @BeforeAll
    static void setup() throws Exception {
        Universal.get().setup(new MySqlTestMethods(dataFolder));
        secondManager = new DatabaseManager();
        secondManager.setup(true);
        setMaximumPoolSize(DatabaseManager.get(), 1);
        setMaximumPoolSize(secondManager, 1);
        assertTrue(DatabaseManager.get().isUseMySQL());
    }

    @Test
    void advisoryLockSerializesIndependentSingleConnectionPools() throws Exception {
        String target = "mysql-lock-user";
        long now = TimeManager.getTime();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DatabaseManager.PunishmentCreationResult> first = executor.submit(
                    () -> createLocked(DatabaseManager.get(), start, target, now));
            Future<DatabaseManager.PunishmentCreationResult> second = executor.submit(
                    () -> createLocked(secondManager, start, target, now));
            start.countDown();
            List<DatabaseManager.PunishmentCreationResult> results = Arrays.asList(first.get(), second.get());
            assertEquals(1, results.stream().filter(result -> result.getStatus()
                    == DatabaseManager.PunishmentCreationResult.Status.CREATED).count());
            assertEquals(1, results.stream().filter(result -> result.getStatus()
                    == DatabaseManager.PunishmentCreationResult.Status.ALREADY_ACTIVE).count());

            int id = results.stream()
                    .filter(result -> result.getStatus() == DatabaseManager.PunishmentCreationResult.Status.CREATED)
                    .findFirst().orElseThrow(AssertionError::new).getId();
            assertTrue(DatabaseManager.get().executeStatementChecked(me.leoko.advancedban.utils.SQLQuery.DELETE_PUNISHMENT, id));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transactionRollbackPreservesExistingRows() {
        Punishment punishment = new Punishment("mysql-rollback", "mysql-rollback", "reason", "JUnit5",
                PunishmentType.WARNING, TimeManager.getTime(), -1, null, -1);
        assertTrue(punishment.createChecked(true));

        assertFalse(DatabaseManager.get().deletePunishmentsAtomically(
                Arrays.asList(punishment.getId(), Integer.MAX_VALUE)));
        assertNotNull(PunishmentManager.get().getPunishment(punishment.getId()));
        assertTrue(punishment.deleteChecked(null, false, true));
    }

    private static DatabaseManager.PunishmentCreationResult createLocked(
            DatabaseManager manager, CountDownLatch start, String target, long now) throws Exception {
        start.await();
        return manager.createPunishmentIfAbsent(false, target, PunishmentType.BAN, now,
                target, target, "reason", "JUnit5", "BAN", now, -1L, null);
    }

    private static void setMaximumPoolSize(DatabaseManager manager, int maximum) throws Exception {
        Field dataSource = DatabaseManager.class.getDeclaredField("dataSource");
        dataSource.setAccessible(true);
        ((HikariDataSource) dataSource.get(manager)).setMaximumPoolSize(maximum);
    }

    @AfterAll
    static void shutdown() {
        if (secondManager != null) {
            secondManager.shutdown();
        }
        Universal.get().shutdown();
    }

    private static final class MySqlTestMethods extends TestMethods {
        private MySqlTestMethods(File dataFolder) {
            super(dataFolder);
        }

        @Override
        public boolean getBoolean(Object file, String path, boolean def) {
            if ("UseMySQL".equals(path)) {
                return true;
            }
            return super.getBoolean(file, path, def);
        }

        @Override
        public Object getMySQLFile() {
            return getConfig();
        }

        @Override
        public String getString(Object file, String path, String def) {
            if ("MySQL.IP".equals(path)) {
                return env("ADVANCEDBAN_MYSQL_HOST", def);
            }
            if ("MySQL.DB-Name".equals(path)) {
                return env("ADVANCEDBAN_MYSQL_DATABASE", "advancedban");
            }
            if ("MySQL.Username".equals(path)) {
                return env("ADVANCEDBAN_MYSQL_USER", "advancedban");
            }
            if ("MySQL.Password".equals(path)) {
                return env("ADVANCEDBAN_MYSQL_PASSWORD", "advancedban");
            }
            if ("MySQL.Properties".equals(path)) {
                return "verifyServerCertificate=false&useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8";
            }
            return super.getString(file, path, def);
        }

        @Override
        public int getInteger(Object file, String path, int def) {
            if ("MySQL.Port".equals(path)) {
                return Integer.parseInt(env("ADVANCEDBAN_MYSQL_PORT", "3306"));
            }
            return super.getInteger(file, path, def);
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isEmpty() ? fallback : value;
        }
    }
}
