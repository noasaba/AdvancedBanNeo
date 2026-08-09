package me.leoko.advancedban.network.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;

/** HMAC-SHA256 operations without retaining or exposing a shared credential. */
public final class HmacSha256 {
    private static final String ALGORITHM = "HmacSHA256";

    private HmacSha256() {
    }

    public static byte[] sign(byte[] key, byte[] message) {
        requireKey(key);
        Objects.requireNonNull(message, "message");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    public static boolean verify(byte[] key, byte[] message, byte[] expectedTag) {
        if (expectedTag == null || expectedTag.length != ProtocolConstants.HMAC_SIZE_BYTES) {
            return false;
        }
        return MessageDigest.isEqual(sign(key, message), expectedTag);
    }

    static void requireKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < ProtocolConstants.MINIMUM_CREDENTIAL_BYTES) {
            throw new IllegalArgumentException("credential must contain at least 32 bytes");
        }
    }
}
