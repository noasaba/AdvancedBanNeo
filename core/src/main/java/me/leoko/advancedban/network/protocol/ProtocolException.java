package me.leoko.advancedban.network.protocol;

/** A malformed or unsupported wire frame. */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
