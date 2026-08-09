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
    MUTATION_RESULT(8);

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
