package me.leoko.advancedban.bukkit.integration.chatsyncer;

import java.util.UUID;

/** Side-effect-free decision boundary shared by both ChatSyncer pipelines. */
interface MuteGate {
    Decision evaluate(UUID playerId);

    final class Decision {
        private static final Decision ALLOW = new Decision(true, "");

        private final boolean allowed;
        private final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason == null ? "" : reason;
        }

        static Decision allow() {
            return ALLOW;
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }

        boolean isAllowed() {
            return allowed;
        }

        String getReason() {
            return reason;
        }
    }
}
