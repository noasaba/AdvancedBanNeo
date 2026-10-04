package me.leoko.advancedban.bukkit.integration.chatsyncer;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.utils.Punishment;

import java.util.List;
import java.util.UUID;

/** Reads only AdvancedBan's in-memory runtime state; this class never performs storage access. */
final class AdvancedBanMuteGate implements MuteGate {
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
            public boolean isFailClosed() {
                return Universal.get().getMethods().isAgentFailClosed();
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
            // If runtime identity is unavailable, failing closed is the only safe choice.
            return Decision.reject("Authority state unavailable; chat is temporarily locked.");
        }
    }

    private Decision evaluateAvailableIdentity(UUID playerId) {
        if (runtime.isAgent() && runtime.isFailClosed() && !runtime.isAgentSnapshotReady()) {
            return Decision.reject("Authority state unavailable; chat is temporarily locked.");
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
        default boolean isFailClosed() { return true; }
        Punishment getRuntimeMute(String uuid);
    }
}
