package me.leoko.advancedban.network.protocol;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Rejects reuse of an authenticated client nonce inside the handshake window. */
public final class HandshakeReplayGuard {
    private static final int MAX_ENTRIES = 4096;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public synchronized boolean accept(String agentIdentity, byte[] nonce, long nowMillis, long retainMillis) {
        if (agentIdentity == null || agentIdentity.trim().isEmpty()
                || agentIdentity.length() > ProtocolConstants.MAX_NODE_ID_BYTES
                || nonce == null || nonce.length < 16 || retainMillis <= 0) {
            return false;
        }
        // A nonce is a network-wide replay token. The claimed Agent UUID is metadata, not a
        // credential namespace, so changing it must not make a captured nonce reusable.
        String key = Base64.getEncoder().encodeToString(nonce);
        Long previousExpiry = seen.get(key);
        if (previousExpiry != null && previousExpiry > nowMillis) {
            return false;
        }
        if (seen.size() >= MAX_ENTRIES) {
            seen.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
            if (seen.size() >= MAX_ENTRIES && previousExpiry == null) {
                return false;
            }
        }
        long expiry = retainMillis > Long.MAX_VALUE - nowMillis
                ? Long.MAX_VALUE : nowMillis + retainMillis;
        seen.put(key, expiry);
        return true;
    }
}
