package me.leoko.advancedban.bungee.listener;

import me.leoko.advancedban.utils.Command;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistrationCompatibilityTest {

    @Test
    void bungeeLeavesPermissionDenialToAdvancedBanMessageHandling() {
        CommandReceiverBungee legacy = new CommandReceiverBungee("ban");
        CommandReceiverBungee permissionAwareCallSite =
                new CommandReceiverBungee("ban", "ab.ban.perma");

        assertNull(legacy.getPermission());
        assertNull(permissionAwareCallSite.getPermission(),
                "Bungee must not reject before CommandManager can send General.NoPerms");
    }

    @Test
    void commandsWithoutCompletersReturnNoSuggestions() {
        assertEquals(Collections.emptyList(), ChatListenerBungee.getSuggestions(
                Command.SYSTEM_PREFERENCES, new Object(), new String[]{""}));
    }

    @Test
    void pluginMessagingUsesAParseableLosslessPunishmentPayload() {
        Punishment punishment = new Punishment("NetworkUser", "network-uuid", "network reason",
                "CONSOLE", PunishmentType.TEMP_BAN, 100L, 200L, "repeat", 42, true);

        JsonObject payload = new JsonParser().parse(InternalListener.serializePunishment(punishment)).getAsJsonObject();

        assertEquals("NetworkUser", payload.get("name").getAsString());
        assertEquals("network-uuid", payload.get("uuid").getAsString());
        assertEquals("TEMP_BAN", payload.get("type").getAsString());
        assertEquals(100L, payload.get("start").getAsLong());
        assertEquals(200L, payload.get("end").getAsLong());
        assertTrue(payload.has("reason"));
        assertTrue(payload.get("silent").getAsBoolean());
    }

    @Test
    void pluginMessagingAcceptsPunishmentsWithoutAnExplicitReason() {
        Punishment original = new Punishment("DefaultReasonUser", "default-reason-uuid", null,
                "CONSOLE", PunishmentType.BAN, 100L, -1L, null, 7, true);
        String json = InternalListener.serializePunishment(original);
        assertFalse(new JsonParser().parse(json).getAsJsonObject().has("reason"));

        Punishment decoded = InternalListener.deserializePunishment(json);
        assertEquals("DefaultReasonUser", decoded.getName());
        assertEquals(PunishmentType.BAN, decoded.getType());
        assertEquals(-1L, decoded.getEnd());
        assertTrue(decoded.isSilent());
    }
}
