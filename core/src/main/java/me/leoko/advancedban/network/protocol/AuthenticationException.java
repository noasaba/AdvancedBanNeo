package me.leoko.advancedban.network.protocol;

/** A well-formed frame which cannot be trusted in the current session. */
public final class AuthenticationException extends Exception {
    public enum Reason {
        BAD_SIGNATURE,
        INCOMPATIBLE_PROTOCOL,
        SOURCE_MISMATCH,
        TARGET_MISMATCH,
        STALE_SESSION,
        STALE_SEQUENCE,
        INVALID_TIMESTAMP,
        SESSION_EXPIRED
    }

    private final Reason reason;

    public AuthenticationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
