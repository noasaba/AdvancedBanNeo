package me.leoko.advancedban.compatibility;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignedChatCompatibilityTest {
    @Test void missingAndUnknownVersionsAreActionable() {
        assertTrue(SignedChatCompatibility.warning(null).contains("all Paper"));
        assertTrue(SignedChatCompatibility.warning("unknown").contains("verify"));
        assertTrue(SignedChatCompatibility.warning("1.5.0-SNAPSHOT").contains("verify"));
    }
    @Test void oldSecurityAndQueueImplementationsAreRejected() {
        for (String version : new String[]{"1.2.4", "1.3.0", "1.4.1"}) {
            assertTrue(SignedChatCompatibility.warning(version).contains("1.5.0"));
        }
    }
    @Test void currentAndLaterStableVersionsHaveNoLocalWarning() {
        assertNull(SignedChatCompatibility.warning("1.5.0"));
        assertNull(SignedChatCompatibility.warning("1.5.1"));
        assertNull(SignedChatCompatibility.warning("2.0.0"));
    }
}
