package me.leoko.advancedban;

import me.leoko.advancedban.manager.MessageManager;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.TimeManager;
import me.leoko.advancedban.manager.UUIDManager;
import me.leoko.advancedban.utils.Command;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import me.leoko.advancedban.utils.commands.PunishmentProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoreRegressionTest {
    @TempDir
    static File dataFolder;

    private static TestMethods methods;

    @BeforeAll
    static void setupUniversal() {
        methods = new TestMethods(dataFolder);
        Universal.get().setup(methods);
    }

    @Test
    @Order(1)
    void shouldTreatMessageParametersAsLiteralText() {
        methods.setMessage("Regression.Literal", "Reason: %REASON%");

        for (String reason : Arrays.asList("$1", "$", "\\")) {
            assertEquals("Reason: " + reason,
                    assertDoesNotThrow(() -> MessageManager.getMessage("Regression.Literal", "REASON", reason)));
        }
    }

    @Test
    @Order(2)
    void shouldRequireASeparateTemporaryPunishmentDurationArgument() {
        assertFalse(Command.TEMP_BAN.validateArguments(new String[]{"foo1d"}));
        assertFalse(Command.TEMP_BAN.validateArguments(new String[]{"foo", "999999999999999999999999d"}));
    }

    @Test
    @Order(3)
    void shouldKeepAllExistingTemporaryPunishmentDurationFormats() {
        for (String duration : Arrays.asList("1w", "2d", "3h", "4m", "5s", "6mo", "#layout")) {
            assertTrue(Command.TEMP_BAN.validateArguments(new String[]{"foo", duration}));
            assertTrue(Command.TEMP_BAN.validateArguments(new String[]{"-s", "foo", duration, "reason"}));
        }
    }

    @Test
    @Order(3)
    void shouldRejectAStandaloneSilentFlagForPermanentPunishments() {
        for (Command command : Arrays.asList(Command.BAN, Command.IP_BAN, Command.MUTE,
                Command.WARN, Command.NOTE, Command.KICK)) {
            assertFalse(command.validateArguments(new String[]{"-s"}));
            assertTrue(command.validateArguments(new String[]{"target"}));
            assertTrue(command.validateArguments(new String[]{"-s", "target"}));
        }
    }

    @Test
    @Order(4)
    void shouldReturnNullForInvalidUuidStrings() {
        UUIDManager manager = UUIDManager.get();

        assertNull(assertDoesNotThrow(() -> manager.fromString(null)));
        assertNull(assertDoesNotThrow(() -> manager.fromString("not-a-uuid")));
        assertNull(assertDoesNotThrow(() -> manager.fromString("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz")));
        assertEquals("123e4567-e89b-12d3-a456-426614174000",
                manager.fromString("123e4567e89b12d3a456426614174000").toString());
    }

    @Test
    @Order(5)
    void shouldCalculateLargeTimeDiffUsingLongArithmetic() {
        methods.setInteger("TimeDiff", 1000);
        long before = System.currentTimeMillis() + 1000L * 60L * 60L * 1000L;
        long actual = TimeManager.getTime();
        long after = System.currentTimeMillis() + 1000L * 60L * 60L * 1000L;

        assertTrue(actual >= before && actual <= after);
        methods.setInteger("TimeDiff", 0);
    }

    @Test
    @Order(6)
    void shouldRemoveExpiredListEntriesWithoutConcurrentModification() {
        long now = TimeManager.getTime();
        new Punishment("expired-list", "expired-list", "reason", "JUnit5",
                PunishmentType.TEMP_BAN, now - 2_000, now - 1_000, null, -1).create(true);

        assertDoesNotThrow(() -> Command.BAN_LIST.execute("UnitTest", new String[0]));
        assertFalse(PunishmentManager.get().isBanned("expired-list"));
    }

    @Test
    @Order(7)
    void shouldHandleOversizedPageAndPunishmentIdsAsUsageErrors() {
        new Punishment("active-list", "active-list", "reason", "JUnit5",
                PunishmentType.BAN, TimeManager.getTime(), -1, null, -1).create(true);
        String oversized = "999999999999999999999999";

        assertDoesNotThrow(() -> Command.BAN_LIST.execute("UnitTest", new String[]{oversized}));
        assertDoesNotThrow(() -> Command.UN_PUNISH.execute("UnitTest", new String[]{oversized}));
        assertDoesNotThrow(() -> Command.CHANGE_REASON.execute("UnitTest", new String[]{oversized, "reason"}));
        assertTrue(methods.getSentMessages().stream().anyMatch(message -> message.contains("Banlist.Usage")));
        assertTrue(methods.getSentMessages().stream().anyMatch(message -> message.contains("UnPunish.Usage")));
        assertTrue(methods.getSentMessages().stream().anyMatch(message -> message.contains("ChangeReason.Usage")));
    }

    @Test
    @Order(8)
    void shouldTreatWarnActionReasonsAsLiteralText() {
        methods.getExecutedCommands().clear();
        for (String reason : Arrays.asList("$1", "$", "\\")) {
            assertDoesNotThrow(() -> new Punishment("warn-literal-" + reason.length(),
                    "warn-literal-" + reason, reason, "JUnit5", PunishmentType.WARNING,
                    TimeManager.getTime(), -1, null, -1).create(true));
            assertTrue(methods.getExecutedCommands().contains("broadcast " + reason));
        }
    }

    @Test
    @Order(9)
    void shouldAllowWeaklyConsistentIterationOfSharedCaches() {
        Set<Punishment> punishments = PunishmentManager.get().getLoadedPunishments(false);
        Punishment one = cachedPunishment("concurrent-one");
        Punishment two = cachedPunishment("concurrent-two");
        Punishment three = cachedPunishment("concurrent-three");
        punishments.add(one);
        punishments.add(two);
        punishments.add(three);
        Iterator<Punishment> punishmentIterator = punishments.iterator();
        assertTrue(punishmentIterator.hasNext());
        punishmentIterator.next();
        punishments.add(cachedPunishment("concurrent-four"));
        assertDoesNotThrow(() -> punishmentIterator.forEachRemaining(ignored -> { }));

        Map<String, String> ips = Universal.get().getIps();
        ips.put("one", "127.0.0.1");
        ips.put("two", "127.0.0.2");
        ips.put("three", "127.0.0.3");
        Iterator<Map.Entry<String, String>> ipIterator = ips.entrySet().iterator();
        assertTrue(ipIterator.hasNext());
        ipIterator.next();
        ips.put("four", "127.0.0.4");
        assertDoesNotThrow(() -> ipIterator.forEachRemaining(ignored -> { }));
    }

    @Test
    @Order(10)
    void shouldHonorConfiguredExemptPlayersWhileTheyAreOnline() throws Exception {
        methods.setOnline(true);
        methods.setPermissions(false);
        methods.setStringList("ExemptPlayers", Arrays.asList("ProtectedPlayer"));

        Method processExempt = PunishmentProcessor.class.getDeclaredMethod("processExempt",
                String.class, String.class, Object.class, PunishmentType.class);
        processExempt.setAccessible(true);
        boolean exempt = (boolean) processExempt.invoke(null,
                "ProtectedPlayer", "protectedplayer", "operator", PunishmentType.BAN);

        methods.setOnline(false);
        methods.setPermissions(true);
        assertTrue(exempt);
    }

    private static Punishment cachedPunishment(String target) {
        return new Punishment(target, target, "reason", "JUnit5", PunishmentType.MUTE,
                TimeManager.getTime(), -1, null, -1);
    }

    @AfterAll
    static void shutdownUniversal() {
        Universal.get().shutdown();
    }
}
