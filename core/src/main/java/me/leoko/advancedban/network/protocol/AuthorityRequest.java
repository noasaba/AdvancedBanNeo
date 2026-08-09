package me.leoko.advancedban.network.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotency-keyed request sent by an Agent to the sole Authority. */
public final class AuthorityRequest {
    public enum Action { COMMAND, CREATE, DELETE, UPDATE_REASON, DELETE_ALL }
    public enum SenderKind { PLAYER, CONSOLE, API }

    private final UUID requestId;
    private final Action action;
    private final SenderKind senderKind;
    private final String senderUuid;
    private final String senderName;
    private final List<String> values;

    public AuthorityRequest(UUID requestId, Action action, SenderKind senderKind,
                            String senderUuid, String senderName, List<String> values) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.action = Objects.requireNonNull(action, "action");
        this.senderKind = Objects.requireNonNull(senderKind, "senderKind");
        this.senderUuid = senderUuid == null ? "" : senderUuid;
        this.senderName = senderName == null ? "" : senderName;
        if (values == null || values.size() > 128) {
            throw new IllegalArgumentException("invalid request values");
        }
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public UUID getRequestId() { return requestId; }
    public Action getAction() { return action; }
    public SenderKind getSenderKind() { return senderKind; }
    public String getSenderUuid() { return senderUuid; }
    public String getSenderName() { return senderName; }
    public List<String> getValues() { return values; }
}
