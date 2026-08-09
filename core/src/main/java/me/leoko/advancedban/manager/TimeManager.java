package me.leoko.advancedban.manager;

import me.leoko.advancedban.Universal;

import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Time Manager is used to have a centralized time for advanced ban which can be different from the system's time.
 */
public class TimeManager {
    private static final Pattern DURATION_PATTERN = Pattern.compile("([0-9]+)(mo|[wdhms])");

    /**
     * Get the current timestamp in milliseconds.
     *
     * @return the timestamp
     */
    public static long getTime() {
        return new Date().getTime() + Universal.get().getMethods().getInteger(
                Universal.get().getMethods().getConfig(), "TimeDiff", 0) * 60L * 60L * 1000L;
    }

    /**
     * Convert a Time String to the amount of milliseconds.
     * These Strings are used for the temporary advancedban punish commands.
     *
     * @param s the time string
     * @return the amount of milliseconds equivalent to the given string
     */
    public static long toMilliSec(String s) {
        if (s == null) {
            return -1;
        }

        Matcher matcher = DURATION_PATTERN.matcher(s.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return -1;
        }

        final long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }

        final long multiplier;
        switch (matcher.group(2)) {
            case "s":
                multiplier = 1000L;
                break;
            case "m":
                multiplier = 1000L * 60L;
                break;
            case "h":
                multiplier = 1000L * 60L * 60L;
                break;
            case "d":
                multiplier = 1000L * 60L * 60L * 24L;
                break;
            case "w":
                multiplier = 1000L * 60L * 60L * 24L * 7L;
                break;
            case "mo":
                multiplier = 1000L * 60L * 60L * 24L * 30L;
                break;
            default:
                return -1;
        }

        try {
            return Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException ignored) {
            return -1;
        }
    }
}
