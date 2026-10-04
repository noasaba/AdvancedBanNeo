package me.leoko.advancedban.compatibility;

/** Local installation diagnostics only; metadata cannot prove backend protocol health. */
public final class SignedChatCompatibility {
    public static final String MINIMUM_VERSION = "1.5.0";
    private static final String INSTALL = "Install matching SignedVelocity-Proxy and SignedVelocity-Paper "
            + MINIMUM_VERSION + " or newer on Velocity and all Paper backends, then restart the network. "
            + "Proxy mute enforcement remains active; an incomplete installation can disconnect muted players. "
            + "See docs/SIGNED-CHAT.md.";

    private SignedChatCompatibility() { }

    /** Returns null when the local version meets the baseline. This is not a remote health check. */
    public static String warning(String version) {
        if (version == null) {
            return "SignedVelocity is missing or disabled. " + INSTALL;
        }
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            return "SignedVelocity version cannot be verified (" + version + "); verify its compatibility. " + INSTALL;
        }
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            Integer.parseInt(parts[2]);
            if (major > 1 || (major == 1 && minor >= 5)) {
                return null;
            }
        } catch (NumberFormatException invalid) {
            return "SignedVelocity version cannot be verified; verify its compatibility. " + INSTALL;
        }
        return "SignedVelocity " + version + " is below the supported signed-chat baseline. " + INSTALL;
    }
}
