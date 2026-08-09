package me.leoko.advancedban;

import me.leoko.advancedban.utils.Command;
import me.leoko.advancedban.utils.PunishmentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompatibilityTest {

    @Test
    void commandSurfaceMatchesAdvancedBan230() {
        List<String> actual = Arrays.stream(Command.values())
                .map(command -> command.name() + "|"
                        + String.join(",", command.getNames()) + "|"
                        + String.valueOf(command.getPermission()) + "|"
                        + String.valueOf(command.getUsagePath()))
                .collect(Collectors.toList());

        assertEquals(Arrays.asList(
                "BAN|ban|ab.ban.perma|Ban.Usage",
                "TEMP_BAN|tempban|ab.ban.temp|Tempban.Usage",
                "IP_BAN|ipban,banip,ban-ip|ab.ipban.perma|Ipban.Usage",
                "TEMP_IP_BAN|tempipban,tipban|ab.ipban.temp|Tempipban.Usage",
                "MUTE|mute|ab.mute.perma|Mute.Usage",
                "TEMP_MUTE|tempmute|ab.mute.temp|Tempmute.Usage",
                "WARN|warn|ab.warn.perma|Warn.Usage",
                "TEMP_WARN|tempwarn|ab.warn.temp|Tempwarn.Usage",
                "NOTE|note|ab.note.use|Note.Usage",
                "KICK|kick|ab.kick.use|Kick.Usage",
                "UN_BAN|unban|ab.Ban.undo|UnBan.Usage",
                "UN_MUTE|unmute|ab.Mute.undo|UnMute.Usage",
                "UN_WARN|unwarn|ab.Warn.undo|UnWarn.Usage",
                "UN_NOTE|unnote|ab.Note.undo|UnNote.Usage",
                "UN_PUNISH|unpunish|ab.all.undo|UnPunish.Usage",
                "CHANGE_REASON|change-reason|ab.changeReason|ChangeReason.Usage",
                "BAN_LIST|banlist|ab.banlist|Banlist.Usage",
                "HISTORY|history|ab.history|History.Usage",
                "WARNS|warns|null|Warns.Usage",
                "NOTES|notes|null|Notes.Usage",
                "CHECK|check|ab.check|Check.Usage",
                "SYSTEM_PREFERENCES|systemprefs|ab.systemprefs|null",
                "ADVANCED_BAN|advancedban|null|null"
        ), actual);

        for (String alias : Arrays.asList("ipban", "banip", "ban-ip", "tempipban", "tipban")) {
            assertTrue(Command.getByName(alias) != null, alias + " must remain registered");
        }
    }

    @Test
    void everyCommandKeepsRepresentativeArgumentValidation() {
        Map<Command, String[]> valid = new EnumMap<>(Command.class);
        Map<Command, String[]> invalid = new EnumMap<>(Command.class);

        for (Command command : Arrays.asList(Command.BAN, Command.IP_BAN, Command.MUTE,
                Command.WARN, Command.NOTE, Command.KICK)) {
            valid.put(command, args("Player"));
            invalid.put(command, args());
        }
        for (Command command : Arrays.asList(Command.TEMP_BAN, Command.TEMP_IP_BAN,
                Command.TEMP_MUTE, Command.TEMP_WARN)) {
            valid.put(command, args("-s", "Player", "1mo", "Reason"));
            invalid.put(command, args("Player", "0d"));
        }

        valid.put(Command.UN_BAN, args("Player"));
        invalid.put(Command.UN_BAN, args("Player", "extra"));
        valid.put(Command.UN_MUTE, args("Player"));
        invalid.put(Command.UN_MUTE, args());
        valid.put(Command.UN_WARN, args("clear", "Player"));
        invalid.put(Command.UN_WARN, args("clear"));
        valid.put(Command.UN_NOTE, args("42"));
        invalid.put(Command.UN_NOTE, args("id"));
        valid.put(Command.UN_PUNISH, args("42"));
        invalid.put(Command.UN_PUNISH, args("-1"));
        valid.put(Command.CHANGE_REASON, args("mute", "Player", "new reason"));
        invalid.put(Command.CHANGE_REASON, args("mute", "Player"));
        valid.put(Command.BAN_LIST, args());
        invalid.put(Command.BAN_LIST, args("0"));
        valid.put(Command.HISTORY, args("Player", "2"));
        invalid.put(Command.HISTORY, args());
        valid.put(Command.WARNS, args());
        invalid.put(Command.WARNS, args("Player", "0"));
        valid.put(Command.NOTES, args("2"));
        invalid.put(Command.NOTES, args("Player", "0"));
        valid.put(Command.CHECK, args("Player"));
        invalid.put(Command.CHECK, args());
        valid.put(Command.SYSTEM_PREFERENCES, args("Player", "ignored"));
        valid.put(Command.ADVANCED_BAN, args("unknown", "ignored"));

        assertEquals(Command.values().length, valid.size(), "Every command needs a golden validation example");
        valid.forEach((command, args) -> assertTrue(command.validateArguments(args),
                command + " should accept " + Arrays.toString(args)));
        invalid.forEach((command, args) -> assertFalse(command.validateArguments(args),
                command + " should reject " + Arrays.toString(args)));

        assertTrue(Command.TEMP_BAN.validateArguments(args("Player", "1w")));
        assertTrue(Command.TEMP_BAN.validateArguments(args("Player", "#EscalatingLayout")));
        assertFalse(Command.TEMP_BAN.validateArguments(args("Player", "1D")),
                "2.3.0 validates time-unit case before TimeManager lower-cases it");
    }

    @Test
    void punishmentTypeSurfaceMatchesAdvancedBan230() {
        List<String> actual = Arrays.stream(PunishmentType.values())
                .map(type -> type.name() + "|" + type.getName() + "|" + type.getBasic().name()
                        + "|" + type.isTemp() + "|" + type.getPerms() + "|" + type.isIpOrientated())
                .collect(Collectors.toList());

        assertEquals(Arrays.asList(
                "BAN|Ban|BAN|false|ab.ban.perma|false",
                "TEMP_BAN|Tempban|BAN|true|ab.ban.temp|false",
                "IP_BAN|Ipban|BAN|false|ab.ipban.perma|true",
                "TEMP_IP_BAN|Tempipban|BAN|true|ab.ipban.temp|true",
                "MUTE|Mute|MUTE|false|ab.mute.perma|false",
                "TEMP_MUTE|Tempmute|MUTE|true|ab.mute.temp|false",
                "WARNING|Warn|WARNING|false|ab.warn.perma|false",
                "TEMP_WARNING|Tempwarn|WARNING|true|ab.warn.temp|false",
                "KICK|Kick|KICK|false|ab.kick.use|false",
                "NOTE|Note|NOTE|false|ab.note.use|false"
        ), actual);
    }

    private static String[] args(String... args) {
        return args;
    }
}
