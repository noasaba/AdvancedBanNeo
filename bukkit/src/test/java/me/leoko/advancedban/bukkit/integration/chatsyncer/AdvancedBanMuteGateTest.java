package me.leoko.advancedban.bukkit.integration.chatsyncer;

import me.leoko.advancedban.utils.Punishment;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedBanMuteGateTest {

    @Test
    void agentWithoutAuthoritySnapshotUsesLastKnownLocalState() {
        FakeRuntime runtime = new FakeRuntime(true, false);
        MuteGate.Decision decision = new AdvancedBanMuteGate(runtime).evaluate(UUID.randomUUID());

        assertTrue(decision.isAllowed());
        assertTrue(runtime.lookupCalled);
    }

    @Test
    void readyAgentUsesDashlessUuidAndAllowsWhenRuntimeMuteIsAbsent() {
        UUID playerId = UUID.randomUUID();
        FakeRuntime runtime = new FakeRuntime(true, true);
        MuteGate.Decision decision = new AdvancedBanMuteGate(runtime).evaluate(playerId);

        assertTrue(decision.isAllowed());
        assertTrue(runtime.lookupCalled);
        assertTrue(runtime.lastUuid.equals(playerId.toString().replace("-", "")));
    }

    @Test
    void agentWithoutSnapshotAllowsWhenFailClosedIsExplicitlyDisabled() {
        FakeRuntime runtime = new FakeRuntime(true, false, false);
        MuteGate.Decision decision = new AdvancedBanMuteGate(runtime).evaluate(UUID.randomUUID());

        assertTrue(decision.isAllowed());
        assertTrue(runtime.lookupCalled);
    }

    @Test
    void nullIdentityIsAllowedWithoutConsultingRuntimeState() {
        FakeRuntime runtime = new FakeRuntime(true, false);
        assertTrue(new AdvancedBanMuteGate(runtime).evaluate(null).isAllowed());
        assertFalse(runtime.lookupCalled);
    }

    @Test
    void runtimeFailureDoesNotLockChat() {
        AdvancedBanMuteGate.RuntimeAccess failing = new AdvancedBanMuteGate.RuntimeAccess() {
            @Override
            public boolean isAgent() {
                throw new IllegalStateException("fixture failure");
            }

            @Override
            public boolean isAgentSnapshotReady() {
                return false;
            }

            @Override
            public Punishment getRuntimeMute(String uuid) {
                throw new IllegalStateException("fixture failure");
            }
        };

        assertTrue(new AdvancedBanMuteGate(failing).evaluate(UUID.randomUUID()).isAllowed());
    }

    private static final class FakeRuntime implements AdvancedBanMuteGate.RuntimeAccess {
        private final boolean agent;
        private final boolean snapshotReady;
        private final boolean failClosed;
        private boolean lookupCalled;
        private String lastUuid;

        private FakeRuntime(boolean agent, boolean snapshotReady) {
            this(agent, snapshotReady, true);
        }

        private FakeRuntime(boolean agent, boolean snapshotReady, boolean failClosed) {
            this.agent = agent;
            this.snapshotReady = snapshotReady;
            this.failClosed = failClosed;
        }

        @Override
        public boolean isAgent() {
            return agent;
        }

        @Override
        public boolean isAgentSnapshotReady() {
            return snapshotReady;
        }

        @Override
        public boolean isFailClosed() {
            return failClosed;
        }

        @Override
        public Punishment getRuntimeMute(String uuid) {
            lookupCalled = true;
            lastUuid = uuid;
            return null;
        }
    }
}
