package me.leoko.advancedban.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VelocityMetadataTest {
    @Test
    void annotationProcessorGeneratesVelocityPluginMetadata() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("velocity-plugin.json"),
                StandardCharsets.UTF_8)) {
            assertNotNull(reader);
            JsonObject metadata = new JsonParser().parse(reader).getAsJsonObject();
            assertEquals("advancedban", metadata.get("id").getAsString());
            assertEquals("AdvancedBan", metadata.get("name").getAsString());
            assertEquals(VelocityMain.class.getName(), metadata.get("main").getAsString());
        }
    }
}
