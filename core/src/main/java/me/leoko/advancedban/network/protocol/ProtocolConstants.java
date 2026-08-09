package me.leoko.advancedban.network.protocol;

/** Constants shared by every AdvancedBan Neo authority/agent transport. */
public final class ProtocolConstants {
    public static final int PROTOCOL_VERSION = 1;
    public static final int WIRE_FORMAT_VERSION = 1;
    public static final int HMAC_SIZE_BYTES = 32;
    public static final int MINIMUM_CREDENTIAL_BYTES = 32;
    public static final int MAX_NODE_ID_BYTES = 128;
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    public static final int MAX_PACKET_BYTES = MAX_PAYLOAD_BYTES + 1024;
    public static final long DEFAULT_CLOCK_SKEW_MILLIS = 30_000L;

    private ProtocolConstants() {
    }
}
