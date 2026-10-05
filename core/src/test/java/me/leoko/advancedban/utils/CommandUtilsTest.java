package me.leoko.advancedban.utils;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandUtilsTest {
    @Test
    void ipBanAcceptsAndNormalizesIpv6Literals() throws Exception {
        String literal = "2001:0db8:0000:0000:0000:ff00:0042:8329";
        Command.CommandInput input = new Command.CommandInput("CONSOLE", new String[]{literal});

        assertEquals(InetAddress.getByName(literal).getHostAddress(), CommandUtils.processIP(input));
    }
}
