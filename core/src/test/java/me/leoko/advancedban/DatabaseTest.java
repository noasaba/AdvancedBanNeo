package me.leoko.advancedban;

import java.io.File;
import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.manager.TimeManager;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by Leo on 07.08.2017.
 */

public class DatabaseTest {

    @TempDir
    public static File dataFolder;

    @BeforeAll
    public static void setupUniversal(){
        Universal.get().setup(new TestMethods(dataFolder));
    }

    @Test
    public void shouldAutomaticallyDetectDatabaseType(){
        // DatabaseManagement behaviour changed in 2.1.9
//        assertFalse("By default no connection with MySQL should be established as it's disabled", DatabaseManager.get().isUseMySQL() );
//        assertFalse("MySQL should not be failed as it should not even try establishing any connection", DatabaseManager.get().isFailedMySQL());
//        assertTrue("The HSQLDB-Connection should be valid", DatabaseManager.get().isConnectionValid(3));
//        DatabaseManager.get().shutdown();
//        DatabaseManager.get().setup(true);
//        assertFalse("Because of a failed connection MySQL should be disabled", DatabaseManager.get().isUseMySQL() );
//        assertTrue("MySQL should be failed as the connection can not succeed", DatabaseManager.get().isFailedMySQL());
    }

    @Test
    public void shutdownIsSafeWhenDataSourceSetupFailed() {
        assertDoesNotThrow(() -> new DatabaseManager().shutdown());
    }

    @Test
    public void punishmentWritesAreTransactionalAndReportSetupFailure() {
        Punishment punishment = new Punishment("database-user", "database-user", "reason", "JUnit5",
                PunishmentType.BAN, TimeManager.getTime(), -1, null, -1);
        assertTrue(punishment.createChecked(true));
        assertTrue(punishment.getId() >= 0);

        assertNull(new DatabaseManager().createPunishment(false,
                "failed-user", "failed-user", "reason", "JUnit5", "BAN",
                TimeManager.getTime(), -1L, null));
    }

    @Test
    public void failedUpdatesDoNotMutatePunishmentOrCache() throws Exception {
        DatabaseManager manager = DatabaseManager.get();
        Field dataSource = DatabaseManager.class.getDeclaredField("dataSource");
        dataSource.setAccessible(true);
        Object activeDataSource = dataSource.get(manager);

        Punishment punishment = new Punishment("checked-user", "checked-user", "original", "JUnit5",
                PunishmentType.BAN, TimeManager.getTime(), -1, null, 99);
        me.leoko.advancedban.manager.PunishmentManager.get().getLoadedPunishments(false).add(punishment);
        try {
            dataSource.set(manager, null);
            assertFalse(punishment.updateReasonChecked("changed"));
            assertEquals("original", punishment.getReason());
            assertFalse(punishment.deleteChecked("JUnit5", false, true));
            assertTrue(me.leoko.advancedban.manager.PunishmentManager.get()
                    .getLoadedPunishments(false).contains(punishment));
        } finally {
            dataSource.set(manager, activeDataSource);
            me.leoko.advancedban.manager.PunishmentManager.get().getLoadedPunishments(false).remove(punishment);
        }
    }

    @Test
    public void missingDatabaseRowsAreNotReportedAsUpdated() {
        Punishment punishment = new Punishment("missing-row", "missing-row", "original", "JUnit5",
                PunishmentType.BAN, TimeManager.getTime(), -1, null, Integer.MAX_VALUE);

        assertFalse(punishment.updateReasonChecked("changed"));
        assertEquals("original", punishment.getReason());
        assertFalse(punishment.deleteChecked("JUnit5", false, true));
    }

    @AfterAll
    public static void shutdownUniversal(){
        Universal.get().shutdown();
    }
}
