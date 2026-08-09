package me.leoko.advancedban.bukkit.listener;

import me.leoko.advancedban.utils.PunishmentType;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalListenerTest {

    @Test
    void ipBanUsesStoredIpWhileNameBanUsesPlayerName() {
        assertEquals("Player", InternalListener.getBanTarget(
                PunishmentType.BAN, "Player", "192.0.2.1"));
        assertEquals("192.0.2.1", InternalListener.getBanTarget(
                PunishmentType.IP_BAN, "Player", "192.0.2.1"));
        assertEquals("192.0.2.1", InternalListener.getBanTarget(
                PunishmentType.TEMP_IP_BAN, "Player", "192.0.2.1"));
    }

    @Test
    void permanentBanHasNoExpirationAndTemporaryBanKeepsEndTime() {
        assertNull(InternalListener.getExpiration(PunishmentType.BAN, -1L));
        assertNull(InternalListener.getExpiration(PunishmentType.IP_BAN, -1L));

        long end = 1_900_000_000_000L;
        assertEquals(new Date(end), InternalListener.getExpiration(PunishmentType.TEMP_BAN, end));
        assertEquals(new Date(end), InternalListener.getExpiration(PunishmentType.TEMP_IP_BAN, end));
    }
}
