package me.leoko.advancedban.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityMetadataTest {
    @Test
    void annotationProcessorGeneratesVelocityPluginMetadata() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("velocity-plugin.json"),
                StandardCharsets.UTF_8)) {
            assertNotNull(reader);
            JsonObject metadata = new JsonParser().parse(reader).getAsJsonObject();
            assertEquals("advancedban", metadata.get("id").getAsString());
            assertEquals("AdvancedBan Neo", metadata.get("name").getAsString());
            assertEquals(VelocityMain.class.getName(), metadata.get("main").getAsString());
            boolean signedDependency = false;
            for (com.google.gson.JsonElement dependency : metadata.getAsJsonArray("dependencies")) {
                JsonObject value = dependency.getAsJsonObject();
                if ("signedvelocity".equals(value.get("id").getAsString())) {
                    assertTrue(value.get("optional").getAsBoolean(), "legacy proxy startup must remain supported");
                    signedDependency = true;
                }
            }
            assertTrue(signedDependency, "SignedVelocity must load before local diagnostics");
        }
    }
}
