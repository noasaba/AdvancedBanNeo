package me.leoko.advancedban.network.state;

/** Punishment types represented in an agent's non-persistent enforcement state. */
public enum RuntimePunishmentType {
    BAN(1),
    TEMP_BAN(2),
    IP_BAN(3),
    TEMP_IP_BAN(4),
    MUTE(5),
    TEMP_MUTE(6),
    WARNING(7),
    TEMP_WARNING(8),
    KICK(9),
    NOTE(10);

    private final int wireId;

    RuntimePunishmentType(int wireId) {
        this.wireId = wireId;
    }

    public boolean isMute() {
        return this == MUTE || this == TEMP_MUTE;
    }

    public boolean isBan() {
        return this == BAN || this == TEMP_BAN || this == IP_BAN || this == TEMP_IP_BAN;
    }

    int getWireId() {
        return wireId;
    }

    static RuntimePunishmentType fromWireId(int wireId) {
        for (RuntimePunishmentType type : values()) {
            if (type.wireId == wireId) {
                return type;
            }
        }
        return null;
    }
}
