package me.leoko.advancedban;

import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.runtime.RuntimeRole;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.SQLQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSnapshotAtomicityTest {
    private static final int OLD_SIZE = 257;
    private static final int NEW_SIZE = 509;

    @TempDir
    File dataFolder;

    private Field roleField;
    private Field methodsField;
    private Field punishmentsField;
    private Field historyField;
    private Field readyField;
    private RuntimeRole oldRole;
    private MethodInterface oldMethods;
    private Object oldPunishments;
    private Object oldHistory;
    private boolean oldReady;

    @BeforeEach
    void installIsolatedAgentContext() throws Exception {
        roleField = field(Universal.class, "runtimeRole");
        methodsField = field(Universal.class, "mi");
        punishmentsField = field(PunishmentManager.class, "punishments");
        historyField = field(PunishmentManager.class, "history");
        readyField = field(PunishmentManager.class, "agentSnapshotReady");

        Universal universal = Universal.get();
        PunishmentManager manager = PunishmentManager.get();
        oldRole = (RuntimeRole) roleField.get(universal);
        oldMethods = (MethodInterface) methodsField.get(universal);
        oldPunishments = punishmentsField.get(manager);
        oldHistory = historyField.get(manager);
        oldReady = readyField.getBoolean(manager);
        roleField.set(universal, RuntimeRole.AGENT_DEGRADED);
        methodsField.set(universal, new TestMethods(dataFolder));
    }

    @AfterEach
    void restoreSharedSingletonState() throws Exception {
        Universal universal = Universal.get();
        PunishmentManager manager = PunishmentManager.get();
        punishmentsField.set(manager, oldPunishments);
        historyField.set(manager, oldHistory);
        readyField.setBoolean(manager, oldReady);
        roleField.set(universal, oldRole);
        methodsField.set(universal, oldMethods);
    }

    @Test
    void concurrentReadersObserveOnlyTheCompleteOldOrCompleteNewSnapshot() throws Exception {
        PunishmentManager manager = PunishmentManager.get();
        List<Punishment> oldSnapshot = snapshot(OLD_SIZE, 0);
        List<Punishment> newSnapshot = snapshot(NEW_SIZE, 10_000);
        manager.replaceAgentSnapshot(oldSnapshot);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean partialSnapshotSeen = new AtomicBoolean();
        Future<?> writer = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 500; i++) {
                manager.replaceAgentSnapshot((i & 1) == 0 ? newSnapshot : oldSnapshot);
            }
            return null;
        });
        Future<?> reader = executor.submit(() -> {
            start.await();
            while (!writer.isDone()) {
                int visibleSize = manager.getLoadedPunishments(false).size();
                if (visibleSize != OLD_SIZE && visibleSize != NEW_SIZE) {
                    partialSnapshotSeen.set(true);
                    break;
                }
            }
            return null;
        });

        start.countDown();
        try {
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertFalse(partialSnapshotSeen.get(),
                "mute enforcement must never observe a clear/add-in-progress snapshot");
        assertTrue(manager.isAgentSnapshotReady());
        int finalSize = manager.getLoadedPunishments(false).size();
        assertTrue(finalSize == OLD_SIZE || finalSize == NEW_SIZE);
    }

    @Test
    void snapshotInputIsCopiedBeforePublication() {
        PunishmentManager manager = PunishmentManager.get();
        Collection<Punishment> mutable = new ArrayList<>(snapshot(OLD_SIZE, 0));

        manager.replaceAgentSnapshot(mutable);
        mutable.clear();

        assertTrue(manager.isAgentSnapshotReady());
        assertEquals(OLD_SIZE, manager.getLoadedPunishments(false).size());
    }

    @Test
    void incrementalMuteUpdateNeverPublishesAnEmptyGap() throws Exception {
        PunishmentManager manager = PunishmentManager.get();
        String target = "0123456789abcdef0123456789abcdef";
        manager.replaceAgentSnapshot(java.util.Collections.singletonList(
                new Punishment("Player", target, "old", "Authority",
                        PunishmentType.MUTE, 1L, -1L, null, 7)));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean gapSeen = new AtomicBoolean();
        Future<?> writer = executor.submit(() -> {
            start.await();
            for (int i = 0; i < 2_000; i++) {
                manager.applyAgentPunishment(new Punishment("Player", target, "reason-" + i,
                        "Authority", PunishmentType.MUTE, 1L, -1L, null, 7));
            }
            return null;
        });
        Future<?> reader = executor.submit(() -> {
            start.await();
            while (!writer.isDone()) {
                if (manager.getRuntimeMute(target) == null) {
                    gapSeen.set(true);
                    break;
                }
            }
            return null;
        });

        start.countDown();
        try {
            writer.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertFalse(gapSeen.get(), "an UPDATE must replace a mute without a fail-open gap");
    }

    @Test
    void historySnapshotSupportsLegacyReadQueriesWithoutDatabaseAccess() {
        PunishmentManager manager = PunishmentManager.get();
        String target = "history-user";
        manager.replaceAgentSnapshot(java.util.Collections.emptyList());
        List<Punishment> historical = java.util.Arrays.asList(
                new Punishment("Player", target, "first", "Authority",
                        PunishmentType.WARNING, 10L, -1L, "warn-layout", 41),
                new Punishment("Player", target, "second", "Authority",
                        PunishmentType.NOTE, 20L, -1L, null, 42));

        manager.replaceAgentHistorySnapshot(historical);

        assertEquals(2, manager.getPunishments(target, null, false).size());
        assertEquals(2, manager.getPunishments(SQLQuery.SELECT_USER_PUNISHMENTS_HISTORY, target).size());
        assertEquals(1, manager.getPunishments(
                SQLQuery.SELECT_USER_PUNISHMENTS_HISTORY_BY_CALCULATION,
                target, "WARN-LAYOUT").size());
        assertEquals(42, manager.getPunishments(
                SQLQuery.SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT, 1).get(0).getId());
        assertEquals(1, manager.getCalculationLevel(target, "warn-layout"));

        manager.appendAgentHistoryPunishment(new Punishment("Player", target, "third", "Authority",
                PunishmentType.WARNING, 30L, -1L, "warn-layout", 43));
        manager.appendAgentHistoryPunishment(new Punishment("Player", target, "replacement", "Authority",
                PunishmentType.WARNING, 30L, -1L, "warn-layout", 43));
        assertEquals(3, manager.getPunishments(target, null, false).size(),
                "replayed history appends must remain idempotent by Authority row id");

        manager.applyAgentPunishment(historical.get(0));
        manager.markAgentSnapshotUnavailable();
        assertTrue(manager.getLoadedHistory().isEmpty(),
                "disconnected Agents must not expose stale history as authoritative");
        assertFalse(manager.getLoadedPunishments(false).isEmpty(),
                "active state must remain available for fail-closed enforcement");
    }

    @Test
    void backendDoesNotBecomeReadyBetweenActiveAndHistorySnapshots() {
        PunishmentManager manager = PunishmentManager.get();
        manager.beginAgentSnapshotSynchronization(snapshot(1, 0));

        assertFalse(manager.isAgentSnapshotReady(),
                "active-only state must not be advertised as a complete API snapshot");

        manager.replaceAgentHistorySnapshot(snapshot(1, 10));
        assertFalse(manager.isAgentSnapshotReady(),
                "installing history alone must not publish readiness before the transport ACK boundary");

        manager.completeAgentSnapshotSynchronization();
        assertTrue(manager.isAgentSnapshotReady());
    }

    private List<Punishment> snapshot(int size, int idOffset) {
        List<Punishment> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new Punishment("Player" + i, "uuid-" + (idOffset + i), "reason", "Authority",
                    PunishmentType.MUTE, 1L, -1L, null, idOffset + i));
        }
        return result;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
