package me.leoko.advancedban.network.state;

import me.leoko.advancedban.network.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPunishmentStateTest {
    private static final long NOW = 1_000_000L;

    @Test
    void applyUpdateRevokeAndDuplicateOperationsAreIdempotent() {
        AgentPunishmentState state = readyState();
        RuntimePunishment mute = punishment(1, RuntimePunishmentType.MUTE, true, -1L, "reason");

        assertTrue(state.apply(mute));
        assertFalse(state.apply(mute));
        assertTrue(state.isMuted("PLAYER-UUID", NOW));

        RuntimePunishment updated = punishment(1, RuntimePunishmentType.MUTE, true, -1L, "new reason");
        assertTrue(state.update(updated));
        assertEquals("new reason", state.getActiveMute("player-uuid", NOW).get().getReason());

        assertTrue(state.revoke(1));
        assertFalse(state.revoke(1));
        assertFalse(state.isMuted("player-uuid", NOW));
    }

    @Test
    void fullSnapshotAtomicallyDropsStalePunishments() {
        AgentPunishmentState state = new AgentPunishmentState();
        state.apply(punishment(1, RuntimePunishmentType.MUTE, true, -1L, "stale"));
        RuntimePunishment current = punishment(2, RuntimePunishmentType.TEMP_MUTE,
                false, NOW + 10_000L, "current");

        state.replaceSnapshot(Collections.singletonList(current));

        assertFalse(state.getActive(1, NOW).isPresent());
        assertTrue(state.getActive(2, NOW).isPresent());
        assertEquals(1, state.size());
        assertThrows(IllegalArgumentException.class,
                () -> state.replaceSnapshot(Arrays.asList(current, current)));
    }

    @Test
    void temporaryPunishmentExpiresWithoutDatabaseAccess() {
        AgentPunishmentState state = readyState();
        state.apply(punishment(3, RuntimePunishmentType.TEMP_MUTE, false, NOW + 100L, "temp"));

        assertTrue(state.isMuted("player-uuid", NOW + 99L));
        assertFalse(state.isMuted("player-uuid", NOW + 100L));
        assertEquals(1, state.removeExpired(NOW + 100L));
        assertEquals(0, state.size());
    }

    @Test
    void expiryCleanupDoesNotRemoveFuturePunishments() {
        AgentPunishmentState state = readyState();
        RuntimePunishment future = new RuntimePunishment(30, "player-uuid", null,
                RuntimePunishmentType.TEMP_MUTE, "future", "console", NOW + 1_000L,
                NOW + 2_000L, false, false, "1s");
        state.apply(future);

        assertEquals(0, state.removeExpired(NOW));
        assertEquals(1, state.size());
        assertFalse(state.isMuted("player-uuid", NOW));
        assertTrue(state.isMuted("player-uuid", NOW + 1_000L));
    }

    @Test
    void uuidAndIpIndexesSelectOnlyActiveMatches() {
        AgentPunishmentState state = readyState();
        state.apply(punishment(4, RuntimePunishmentType.BAN, true, -1L, "uuid"));
        state.apply(new RuntimePunishment(5, null, "192.0.2.4", RuntimePunishmentType.IP_BAN,
                "ip", "console", NOW, -1L, true, false, ""));

        assertEquals(1, state.getActiveForUuid("PLAYER-UUID", NOW).size());
        assertEquals(1, state.getActiveForIp("192.0.2.4", NOW).size());
        assertTrue(state.getActiveForIp("192.0.2.5", NOW).isEmpty());
    }

    @Test
    void runtimePayloadsRoundTripAndRejectMalformedInput() throws Exception {
        RuntimePunishmentCodec codec = new RuntimePunishmentCodec();
        RuntimePunishment first = punishment(6, RuntimePunishmentType.TEMP_MUTE,
                false, NOW + 5_000L, "reason");
        RuntimePunishment second = new RuntimePunishment(7, "ExamplePlayer", null, "2001:db8::1",
                RuntimePunishmentType.IP_BAN, "network", "staff", NOW, -1L,
                true, true, "permanent");

        assertEquals(first, codec.decode(codec.encode(first)));
        List<RuntimePunishment> snapshot = codec.decodeSnapshot(codec.encodeSnapshot(Arrays.asList(first, second)));
        assertEquals(Arrays.asList(first, second), snapshot);
        assertEquals("ExamplePlayer", snapshot.get(1).getTargetName());
        assertEquals(7L, codec.decodeRevoke(codec.encodeRevoke(7L)));
        assertThrows(ProtocolException.class, () -> codec.decode(new byte[]{1, 2, 3}));
        assertThrows(ProtocolException.class, () -> codec.decodeSnapshot(new byte[]{2, 0, 0, 0, 0}));
    }

    @Test
    void concurrentUpdatesDoNotLosePunishments() throws Exception {
        AgentPunishmentState state = readyState();
        int workers = 4;
        int perWorker = 50;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            executor.submit(() -> {
                start.await();
                for (int i = 0; i < perWorker; i++) {
                    long id = workerId * perWorker + i;
                    state.apply(punishment(id, RuntimePunishmentType.MUTE, true, -1L, "r"));
                }
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(workers * perWorker, state.size());
    }

    @Test
    void startupAndDisconnectRemainFailClosedUntilFullSnapshot() {
        AgentPunishmentState state = new AgentPunishmentState();

        assertEquals(AgentPunishmentState.MuteStatus.STATE_UNKNOWN,
                state.getMuteStatus("player-uuid", NOW));
        assertTrue(state.isMuted("player-uuid", NOW), "unknown state must not bypass a mute");

        state.replaceSnapshot(Collections.emptyList());
        assertEquals(AgentPunishmentState.MuteStatus.NOT_MUTED,
                state.getMuteStatus("player-uuid", NOW));
        assertFalse(state.isMuted("player-uuid", NOW));

        state.markUnavailable();
        assertEquals(AgentPunishmentState.MuteStatus.STATE_UNKNOWN,
                state.getMuteStatus("player-uuid", NOW));
        assertTrue(state.isMuted("player-uuid", NOW), "degraded state must fail closed");
    }

    private AgentPunishmentState readyState() {
        AgentPunishmentState state = new AgentPunishmentState();
        state.replaceSnapshot(Collections.emptyList());
        return state;
    }

    private RuntimePunishment punishment(long id, RuntimePunishmentType type, boolean permanent,
                                          long end, String reason) {
        return new RuntimePunishment(id, "player-uuid", null, type, reason, "console",
                NOW, end, permanent, false, permanent ? "permanent" : "10s");
    }
}
