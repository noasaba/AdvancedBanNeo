package me.leoko.advancedban;

import me.leoko.advancedban.utils.FloodgateIdentity;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloodgateIdentityTest {
    @Test
    void detectsFloodgateIdentityThroughOptionalPublicApi() {
        UUID bedrockId = UUID.fromString("00000000-0000-0000-0009-01fa02c95a2e");
        FloodgateApi.setFloodgateUuid(bedrockId);

        assertTrue(FloodgateIdentity.isFloodgatePlayer(FloodgateApi.class.getClassLoader(), bedrockId));
        assertFalse(FloodgateIdentity.isFloodgatePlayer(FloodgateApi.class.getClassLoader(), UUID.randomUUID()));
        assertFalse(FloodgateIdentity.isFloodgatePlayer(new ClassLoader(null) { }, bedrockId));
        assertFalse(FloodgateIdentity.isFloodgatePlayer(FloodgateApi.class.getClassLoader(), null));
    }
}
