package me.leoko.advancedban.network.protocol;

/** Transport-neutral messages exchanged by the coordinator and an agent. */
public enum MessageKind {
    SNAPSHOT(1),
    APPLY(2),
    UPDATE(3),
    REVOKE(4),
    HEARTBEAT(5),
    ACK(6),
    MUTATION_REQUEST(7),
    MUTATION_RESULT(8),
    HISTORY_SNAPSHOT_BEGIN(9),
    HISTORY_SNAPSHOT_CHUNK(10),
    HISTORY_SNAPSHOT_END(11),
    HISTORY_APPEND(12),
    /** Agent confirms that both bootstrap snapshots are installed. */
    READY(13);

    private final int wireId;

    MessageKind(int wireId) {
        this.wireId = wireId;
    }

    int getWireId() {
        return wireId;
    }

    static MessageKind fromWireId(int wireId) {
        for (MessageKind kind : values()) {
            if (kind.wireId == wireId) {
                return kind;
            }
        }
        return null;
    }
}
