package me.leoko.advancedban;

import me.leoko.advancedban.utils.Command;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential corpus captured from the AdvancedBan 2.3.0 command validators.
 * Deliberate parser hardening differences are named explicitly instead of being
 * silently folded into a hand-written happy-path test.
 */
class AdvancedBan230DifferentialCommandTest {

    private static final Map<Command, Pattern> LEGACY = legacyValidators();

    @Test
    void validationMatchesAdvancedBan230ForFullBoundaryCorpus() {
        List<String> allowedHardening = new ArrayList<>();
        for (Command command : Command.values()) {
            Pattern legacy = LEGACY.get(command);
            assertTrue(legacy != null, "Missing 2.3.0 validator for " + command);
            for (String[] arguments : corpus(command)) {
                boolean expected = legacy.matcher(String.join(" ", arguments)).matches();
                boolean actual = command.validateArguments(arguments);
                if (expected != actual && isAllowedHardening(command, arguments, expected, actual)) {
                    allowedHardening.add(command + " " + Arrays.toString(arguments));
                    continue;
                }
                assertEquals(expected, actual,
                        command + " changed validation for " + Arrays.toString(arguments));
            }
        }

        assertEquals(Arrays.asList(
                "BAN [-s]", "BAN [-S]",
                "TEMP_BAN [Player, 999999999999999999999999d]",
                "IP_BAN [-s]", "IP_BAN [-S]",
                "TEMP_IP_BAN [Player, 999999999999999999999999d]",
                "MUTE [-s]", "MUTE [-S]",
                "TEMP_MUTE [Player, 999999999999999999999999d]",
                "WARN [-s]", "WARN [-S]",
                "TEMP_WARN [Player, 999999999999999999999999d]",
                "NOTE [-s]", "NOTE [-S]", "KICK [-s]", "KICK [-S]"
        ), allowedHardening, "Every differential must remain explicitly reviewed");
    }

    private static boolean isAllowedHardening(Command command, String[] arguments,
                                                boolean legacy, boolean current) {
        if (!legacy || current) {
            return false;
        }
        switch (command) {
            case BAN:
            case IP_BAN:
            case MUTE:
            case WARN:
            case NOTE:
            case KICK:
                return Arrays.equals(arguments, new String[]{"-s"})
                        || Arrays.equals(arguments, new String[]{"-S"});
            case TEMP_BAN:
            case TEMP_IP_BAN:
            case TEMP_MUTE:
            case TEMP_WARN:
                return Arrays.equals(arguments,
                        new String[]{"Player", "999999999999999999999999d"});
            default:
                return false;
        }
    }

    private static List<String[]> corpus(Command command) {
        List<String[]> values = new ArrayList<>(Arrays.asList(
                args(), args(""), args("Player"), args("Player", "Reason"),
                args("-s"), args("-S"), args("-s", "Player"), args("Player", "-s"),
                args("0"), args("1"), args("01"), args("-1"), args("2147483647"),
                args("2147483648"), args("clear"), args("clear", "Player"),
                args("CLEAR", "Player"), args("ban", "Player", "Reason"),
                args("mute", "Player", "Reason"), args("unknown", "ignored")
        ));
        if (command == Command.TEMP_BAN || command == Command.TEMP_IP_BAN
                || command == Command.TEMP_MUTE || command == Command.TEMP_WARN) {
            values.addAll(Arrays.asList(
                    args("Player", "1s"), args("Player", "1m"), args("Player", "1h"),
                    args("Player", "1d"), args("Player", "1w"), args("Player", "1mo"),
                    args("Player", "1D"), args("Player", "0d"), args("Player", "01d"),
                    args("Player", "#Layout"), args("Player", "#"),
                    args("-s", "Player", "1d"), args("-S", "Player", "1d"),
                    args("Player", "999999999999999999999999d")
            ));
        }
        return values;
    }

    private static Map<Command, Pattern> legacyValidators() {
        Map<Command, Pattern> validators = new EnumMap<>(Command.class);
        for (Command command : Arrays.asList(Command.BAN, Command.IP_BAN, Command.MUTE,
                Command.WARN, Command.NOTE, Command.KICK)) {
            validators.put(command, Pattern.compile(".+"));
        }
        Pattern temporary = Pattern.compile("(-s )?\\S+ ?([1-9][0-9]*([wdhms]|mo)|#.+)( .*)?");
        for (Command command : Arrays.asList(Command.TEMP_BAN, Command.TEMP_IP_BAN,
                Command.TEMP_MUTE, Command.TEMP_WARN)) {
            validators.put(command, temporary);
        }
        validators.put(Command.UN_BAN, Pattern.compile("\\S+"));
        validators.put(Command.UN_MUTE, Pattern.compile("\\S+"));
        validators.put(Command.UN_WARN, Pattern.compile("[0-9]+|(?i:clear \\S+)"));
        validators.put(Command.UN_NOTE, Pattern.compile("[0-9]+|(?i:clear \\S+)"));
        validators.put(Command.UN_PUNISH, Pattern.compile("[0-9]+"));
        validators.put(Command.CHANGE_REASON,
                Pattern.compile("([0-9]+|(?i)(ban|mute) \\S+) .+"));
        validators.put(Command.BAN_LIST, Pattern.compile("([1-9][0-9]*)?"));
        validators.put(Command.HISTORY, Pattern.compile("\\S+( [1-9][0-9]*)?"));
        validators.put(Command.WARNS, Pattern.compile("\\S+( [1-9][0-9]*)?|\\S+|"));
        validators.put(Command.NOTES, Pattern.compile("\\S+( [1-9][0-9]*)?|\\S+|"));
        validators.put(Command.CHECK, Pattern.compile("\\S+"));
        validators.put(Command.SYSTEM_PREFERENCES, Pattern.compile(".*"));
        validators.put(Command.ADVANCED_BAN, Pattern.compile(".*"));
        return validators;
    }

    private static String[] args(String... values) {
        return values;
    }
}
