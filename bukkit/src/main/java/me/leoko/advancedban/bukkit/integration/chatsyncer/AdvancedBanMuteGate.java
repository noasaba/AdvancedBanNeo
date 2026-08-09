package me.leoko.advancedban.bukkit.integration.chatsyncer;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.utils.Punishment;

import java.util.List;
import java.util.UUID;

/** Reads only AdvancedBan's in-memory runtime state; this class never performs storage access. */
final class AdvancedBanMuteGate implements MuteGate {
    private static final String SNAPSHOT_UNAVAILABLE =
            "AdvancedBan mute state is synchronizing. Please try again shortly.";
    private static final String FALLBACK_MUTE_MESSAGE = "You are muted.";
    private final RuntimeAccess runtime;

    AdvancedBanMuteGate() {
        this(new RuntimeAccess() {
            @Override
            public boolean isAgent() {
                return Universal.get().getRuntimeRole().isAgent();
            }

            @Override
            public boolean isAgentSnapshotReady() {
                return PunishmentManager.get().isAgentSnapshotReady();
            }

            @Override
            public Punishment getRuntimeMute(String uuid) {
                return PunishmentManager.get().getRuntimeMute(uuid);
            }
        });
    }

    AdvancedBanMuteGate(RuntimeAccess runtime) {
        this.runtime = runtime;
    }

    @Override
    public Decision evaluate(UUID playerId) {
        if (playerId == null) {
            return Decision.allow();
        }

        try {
            return evaluateAvailableIdentity(playerId);
        } catch (RuntimeException failure) {
            // ChatEventBus is fail-open for listener exceptions. Convert an
            // unexpected runtime-state failure into an explicit rejection so
            // it cannot become a mute bypass.
            return Decision.reject(SNAPSHOT_UNAVAILABLE);
        }
    }

    private Decision evaluateAvailableIdentity(UUID playerId) {
        if (runtime.isAgent() && !runtime.isAgentSnapshotReady()) {
            return Decision.reject(SNAPSHOT_UNAVAILABLE);
        }

        Punishment mute = runtime.getRuntimeMute(playerId.toString().replace("-", ""));
        if (mute == null) {
            return Decision.allow();
        }

        List<String> layout = mute.getLayout();
        return Decision.reject(layout == null || layout.isEmpty()
                ? FALLBACK_MUTE_MESSAGE
                : String.join("\n", layout));
    }

    interface RuntimeAccess {
        boolean isAgent();
        boolean isAgentSnapshotReady();
        Punishment getRuntimeMute(String uuid);
    }
}
