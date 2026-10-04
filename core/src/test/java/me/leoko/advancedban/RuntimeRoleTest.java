package me.leoko.advancedban;

import me.leoko.advancedban.manager.DatabaseManager;
import me.leoko.advancedban.manager.CommandManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.runtime.RuntimeRole;
import me.leoko.advancedban.utils.SQLQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RuntimeRoleTest {
    @TempDir
    File dataFolder;

    private Field roleField;
    private Field methodsField;
    private RuntimeRole oldRole;
    private MethodInterface oldMethods;

    @BeforeEach
    void installAgentContext() throws Exception {
        roleField = Universal.class.getDeclaredField("runtimeRole");
        roleField.setAccessible(true);
        methodsField = Universal.class.getDeclaredField("mi");
        methodsField.setAccessible(true);
        oldRole = (RuntimeRole) roleField.get(Universal.get());
        oldMethods = (MethodInterface) methodsField.get(Universal.get());
        roleField.set(Universal.get(), RuntimeRole.AGENT_DEGRADED);
        methodsField.set(Universal.get(), new TestMethods(dataFolder));
        PunishmentManager.get().markAgentSnapshotUnavailable();
    }

    @AfterEach
    void restoreContext() throws Exception {
        roleField.set(Universal.get(), oldRole);
        methodsField.set(Universal.get(), oldMethods);
    }

    @Test
    void agentCannotInitializeReadOrWritePunishmentDatabase() {
        DatabaseManager database = new DatabaseManager();
        database.setup(false);

        assertFalse(database.isConnectionValid());
        assertNull(database.executeResultStatement(SQLQuery.SELECT_ALL_PUNISHMENTS));
        assertFalse(database.executeStatementChecked(SQLQuery.DELETE_PUNISHMENT, 1));
        assertNull(database.createPunishment(false, "name", "uuid", "reason", "operator",
                "BAN", 1L, -1L, null));
        assertFalse(database.deletePunishmentsAtomically(java.util.Collections.singletonList(1)));
    }

    @Test
    void agentHealthTransitionsNeverPromoteToAuthority() {
        Universal.get().updateAgentRole(RuntimeRole.AGENT);
        assertTrue(Universal.get().getRuntimeRole().isAgent());
        Universal.get().updateAgentRole(RuntimeRole.AGENT_DEGRADED);
        assertThrows(IllegalStateException.class,
                () -> Universal.get().updateAgentRole(RuntimeRole.STANDALONE_AUTHORITY));
    }

    @Test
    void agentBuiltInCommandIsDelegatedWithoutLocalExecution() throws Exception {
        RecordingAgentMethods methods = new RecordingAgentMethods(dataFolder);
        methodsField.set(Universal.get(), methods);

        CommandManager.get().onCommand("sender", "note", new String[]{"Target", "reason"});

        assertEquals("note", methods.command);
        assertArrayEquals(new String[]{"Target", "reason"}, methods.arguments);
    }

    @Test
    void degradedAgentDoesNotRejectLoginBecauseAuthorityIsUnavailable() {
        assertNotEquals("[AdvancedBan] Authority state unavailable; login is temporarily locked.",
                Universal.get().callConnection("Target", "127.0.0.1"));
    }

    @Test
    void degradedAgentDoesNotApplyAuthorityLockWhenFailClosedIsDisabled() throws Exception {
        methodsField.set(Universal.get(), new FailOpenAgentMethods(dataFolder));
        assertNotEquals("[AdvancedBan] Authority state unavailable; login is temporarily locked.",
                Universal.get().callConnection("Target", "127.0.0.1"));
    }

    @Test
    void coordinatorCannotBootstrapWithoutItsSoleAuthorityDatabase() {
        assertThrows(IllegalStateException.class,
                () -> Universal.verifyAuthorityStorage(RuntimeRole.COORDINATOR_AUTHORITY, false));
        Universal.verifyAuthorityStorage(RuntimeRole.COORDINATOR_AUTHORITY, true);
        Universal.verifyAuthorityStorage(RuntimeRole.STANDALONE_AUTHORITY, false);
    }

    @Test
    void offlineForwardedUuidPreservesLegacyNameIdentityAcrossAgent() {
        String username = "E2EPlayer";
        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));

        assertTrue(Universal.isOfflineModeUuid(username, offlineUuid));
        assertFalse(Universal.isOfflineModeUuid(username, UUID.randomUUID()));
    }

    private static final class RecordingAgentMethods extends TestMethods {
        private String command;
        private String[] arguments;

        private RecordingAgentMethods(File dataFolder) {
            super(dataFolder);
        }

        @Override
        public boolean submitAuthorityCommand(Object sender, String command, String[] arguments) {
            this.command = command;
            this.arguments = arguments.clone();
            return true;
        }
    }

    private static final class FailOpenAgentMethods extends TestMethods {
        private FailOpenAgentMethods(File dataFolder) {
            super(dataFolder);
        }

        @Override
        public boolean isAgentFailClosed() {
            return false;
        }
    }
}
