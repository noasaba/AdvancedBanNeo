package me.leoko.advancedban.network.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-safe, non-persistent punishment view for an agent.
 * Reads use a single immutable state reference; snapshots replace all old data atomically.
 */
public final class AgentPunishmentState {
    public enum MuteStatus {
        MUTED,
        NOT_MUTED,
        STATE_UNKNOWN
    }

    private volatile State state = State.empty();
    private volatile boolean snapshotReady;

    public synchronized boolean apply(RuntimePunishment punishment) {
        Objects.requireNonNull(punishment, "punishment");
        RuntimePunishment previous = state.byId.get(punishment.getId());
        if (punishment.equals(previous)) {
            return false;
        }
        Map<Long, RuntimePunishment> next = new HashMap<>(state.byId);
        next.put(punishment.getId(), punishment);
        state = State.of(next);
        return true;
    }

    public boolean update(RuntimePunishment punishment) {
        return apply(punishment);
    }

    public synchronized boolean revoke(long punishmentId) {
        if (!state.byId.containsKey(punishmentId)) {
            return false;
        }
        Map<Long, RuntimePunishment> next = new HashMap<>(state.byId);
        next.remove(punishmentId);
        state = State.of(next);
        return true;
    }

    /** Replaces, rather than merges, state so revoked punishments cannot survive a reconnect. */
    public synchronized void replaceSnapshot(Collection<RuntimePunishment> punishments) {
        Objects.requireNonNull(punishments, "punishments");
        Map<Long, RuntimePunishment> next = new HashMap<>();
        for (RuntimePunishment punishment : punishments) {
            Objects.requireNonNull(punishment, "snapshot punishment");
            if (next.put(punishment.getId(), punishment) != null) {
                throw new IllegalArgumentException("duplicate punishment id in snapshot: " + punishment.getId());
            }
        }
        state = State.of(next);
        snapshotReady = true;
    }

    /** Marks cached data untrusted after disconnect; the next full snapshot makes it ready again. */
    public void markUnavailable() {
        snapshotReady = false;
    }

    public boolean isSnapshotReady() {
        return snapshotReady;
    }

    public Optional<RuntimePunishment> getActive(long punishmentId, long nowMillis) {
        RuntimePunishment punishment = state.byId.get(punishmentId);
        return punishment != null && punishment.isActive(nowMillis)
                ? Optional.of(punishment) : Optional.empty();
    }

    public List<RuntimePunishment> getActiveForUuid(String uuid, long nowMillis) {
        State current = state;
        return getActive(current, current.byUuid.get(normalize(uuid)), nowMillis);
    }

    public List<RuntimePunishment> getActiveForIp(String ip, long nowMillis) {
        State current = state;
        return getActive(current, current.byIp.get(normalize(ip)), nowMillis);
    }

    public Optional<RuntimePunishment> getActiveMute(String uuid, long nowMillis) {
        for (RuntimePunishment punishment : getActiveForUuid(uuid, nowMillis)) {
            if (punishment.getType().isMute()) {
                return Optional.of(punishment);
            }
        }
        return Optional.empty();
    }

    public boolean isMuted(String uuid, long nowMillis) {
        return getMuteStatus(uuid, nowMillis) == MuteStatus.MUTED;
    }

    /**
     * Returns STATE_UNKNOWN until a full snapshot is installed. Callers may continue with the last known state.
     */
    public MuteStatus getMuteStatus(String uuid, long nowMillis) {
        if (!snapshotReady) {
            return getActiveMute(uuid, nowMillis).isPresent() ? MuteStatus.MUTED : MuteStatus.STATE_UNKNOWN;
        }
        return getActiveMute(uuid, nowMillis).isPresent() ? MuteStatus.MUTED : MuteStatus.NOT_MUTED;
    }

    public List<RuntimePunishment> snapshot() {
        List<RuntimePunishment> result = new ArrayList<>(state.byId.values());
        result.sort((left, right) -> Long.compare(left.getId(), right.getId()));
        return Collections.unmodifiableList(result);
    }

    public int size() {
        return state.byId.size();
    }

    public synchronized int removeExpired(long nowMillis) {
        Map<Long, RuntimePunishment> next = new HashMap<>(state.byId);
        int originalSize = next.size();
        next.values().removeIf(punishment -> punishment.isExpired(nowMillis));
        if (next.size() != originalSize) {
            state = State.of(next);
        }
        return originalSize - next.size();
    }

    private List<RuntimePunishment> getActive(State current, Set<Long> ids, long nowMillis) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<RuntimePunishment> result = new ArrayList<>();
        for (Long id : ids) {
            RuntimePunishment punishment = current.byId.get(id);
            if (punishment != null && punishment.isActive(nowMillis)) {
                result.add(punishment);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class State {
        private final Map<Long, RuntimePunishment> byId;
        private final Map<String, Set<Long>> byUuid;
        private final Map<String, Set<Long>> byIp;

        private State(Map<Long, RuntimePunishment> byId, Map<String, Set<Long>> byUuid,
                      Map<String, Set<Long>> byIp) {
            this.byId = byId;
            this.byUuid = byUuid;
            this.byIp = byIp;
        }

        private static State empty() {
            return new State(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        }

        private static State of(Map<Long, RuntimePunishment> punishments) {
            Map<Long, RuntimePunishment> ids = Collections.unmodifiableMap(new HashMap<>(punishments));
            Map<String, Set<Long>> uuids = new HashMap<>();
            Map<String, Set<Long>> ips = new HashMap<>();
            for (RuntimePunishment punishment : ids.values()) {
                add(uuids, punishment.getTargetUuid(), punishment.getId());
                add(ips, punishment.getTargetIp(), punishment.getId());
            }
            return new State(ids, freeze(uuids), freeze(ips));
        }

        private static void add(Map<String, Set<Long>> index, String key, long id) {
            if (key != null) {
                index.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
            }
        }

        private static Map<String, Set<Long>> freeze(Map<String, Set<Long>> index) {
            Map<String, Set<Long>> frozen = new HashMap<>();
            for (Map.Entry<String, Set<Long>> entry : index.entrySet()) {
                frozen.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(frozen);
        }
    }
}
