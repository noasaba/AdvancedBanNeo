package me.leoko.advancedban;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.TimeManager;
import me.leoko.advancedban.utils.Command;
import me.leoko.advancedban.utils.InterimData;
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
        assertFalse(DatabaseManager.get().isUseMySQL());
        assertTrue(DatabaseManager.get().isConnectionValid());
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

    @Test
    public void failedExpiryDeleteKeepsCachedPunishmentConsistent() throws Exception {
        DatabaseManager manager = DatabaseManager.get();
        Field dataSource = DatabaseManager.class.getDeclaredField("dataSource");
        dataSource.setAccessible(true);
        Object activeDataSource = dataSource.get(manager);

        String uuid = "expired-delete-failure";
        Punishment punishment = new Punishment("expired-user", uuid, "reason", "JUnit5",
                PunishmentType.TEMP_BAN, TimeManager.getTime() - 2_000L,
                TimeManager.getTime() - 1_000L, null, 100);
        new InterimData(uuid, "expired-user", null,
                Collections.singleton(punishment), Collections.emptySet()).accept();
        try {
            dataSource.set(manager, null);
            assertTrue(PunishmentManager.get().getPunishments(uuid, PunishmentType.BAN, true).isEmpty());
            assertNull(PunishmentManager.get().getPunishment(100));
            assertTrue(PunishmentManager.get().getLoadedPunishments(false).contains(punishment),
                    "cache must remain so database cleanup can be retried");
        } finally {
            dataSource.set(manager, activeDataSource);
            PunishmentManager.get().getLoadedPunishments(false).remove(punishment);
            PunishmentManager.get().discard("expired-user");
        }
    }

    @Test
    public void removalClearsEveryCachedCopyOfTheSameDatabaseRow() {
        Punishment first = new Punishment("duplicate-user", "duplicate-user", "first", "JUnit5",
                PunishmentType.MUTE, TimeManager.getTime(), -1, null, 101);
        Punishment second = new Punishment("duplicate-user", "duplicate-user", "second", "JUnit5",
                PunishmentType.MUTE, TimeManager.getTime(), -1, null, 101);
        PunishmentManager.get().getLoadedPunishments(false).add(first);
        PunishmentManager.get().getLoadedPunishments(false).add(second);

        PunishmentManager.get().removeLoadedPunishment(101);

        assertFalse(PunishmentManager.get().getLoadedPunishments(false).contains(first));
        assertFalse(PunishmentManager.get().getLoadedPunishments(false).contains(second));
        assertDoesNotThrow(() -> PunishmentManager.get().discard(null));
    }

    @Test
    public void massDeleteRollsBackWhenAnyPunishmentIsMissing() {
        Punishment punishment = new Punishment("rollback-user", "rollback-user", "reason", "JUnit5",
                PunishmentType.WARNING, TimeManager.getTime(), -1, null, -1);
        assertTrue(punishment.createChecked(true));

        assertFalse(DatabaseManager.get().deletePunishmentsAtomically(
                Arrays.asList(punishment.getId(), Integer.MAX_VALUE)));
        assertTrue(PunishmentManager.get().getPunishment(punishment.getId()) != null,
                "the first delete must be rolled back");

        assertTrue(punishment.deleteChecked(null, false, true));
    }

    @Test
    public void massDeleteCommitsAllPunishmentsTogether() {
        String target = "mass-clear-user";
        Punishment first = new Punishment(target, target, "first", "JUnit5",
                PunishmentType.WARNING, TimeManager.getTime(), -1, null, -1);
        Punishment second = new Punishment(target, target, "second", "JUnit5",
                PunishmentType.WARNING, TimeManager.getTime(), -1, null, -1);
        assertTrue(first.createChecked(true));
        assertTrue(second.createChecked(true));

        List<Punishment> warnings = PunishmentManager.get().getWarns(target);
        assertEquals(2, warnings.size());
        assertTrue(Punishment.deleteAllChecked(warnings, "JUnit5", true, true));
        assertTrue(PunishmentManager.get().getWarns(target).isEmpty());
    }

    @Test
    public void concurrentCommandsCreateOnlyOneActiveBan() throws Exception {
        String target = "race-ban-user";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> executeBan(start, target));
            Future<?> second = executor.submit(() -> executeBan(start, target));
            start.countDown();
            first.get();
            second.get();

            List<Punishment> active = PunishmentManager.get().getPunishments(target, PunishmentType.BAN, true);
            assertEquals(1, active.size());
            assertTrue(active.get(0).deleteChecked(null, false, true));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void executeBan(CountDownLatch start, String target) {
        try {
            start.await();
            Command.BAN.execute("JUnit5", new String[]{"-s", target, "reason"});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @AfterAll
    public static void shutdownUniversal(){
        Universal.get().shutdown();
    }
}
