package me.leoko.advancedban.velocity;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigTest {
    @Test
    void readsExistingAdvancedBanConfigurationWithoutMigration() throws Exception {
        URL resource = getClass().getClassLoader().getResource("config.yml");
        assertTrue(resource != null && "file".equals(resource.getProtocol()));

        YamlConfig config = YamlConfig.load(Paths.get(resource.toURI()));
        assertFalse(config.stringList("MuteCommands").isEmpty());
        assertEquals("false", String.valueOf(config.get("UseMySQL")));
        assertTrue(config.keys("UUID-Fetcher").contains("REST-API"));
        assertEquals("600", String.valueOf(config.get("TempPerms.1")));
    }
}
