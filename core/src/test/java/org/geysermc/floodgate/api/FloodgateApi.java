package org.geysermc.floodgate.api;

import java.util.UUID;

/** Test-only stand-in for Floodgate's optional public API. */
public final class FloodgateApi {
    private static final FloodgateApi INSTANCE = new FloodgateApi();
    private static UUID floodgateUuid;

    private FloodgateApi() {
    }

    public static FloodgateApi getInstance() {
        return INSTANCE;
    }

    public boolean isFloodgatePlayer(UUID uuid) {
        return uuid != null && uuid.equals(floodgateUuid);
    }

    public static void setFloodgateUuid(UUID uuid) {
        floodgateUuid = uuid;
    }
}
