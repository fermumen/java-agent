package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHealthPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void optionalStartupFailureDegradesButRequiredFailureBlocks() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path optional = temporary.resolve("optional.json");
        Files.writeString(optional, config(false));
        try (McpRuntime runtime = McpRuntime.load(json, optional)) {
            assertTrue(runtime.tools().isEmpty());
        }

        Path required = temporary.resolve("required.json");
        Files.writeString(required, config(true));
        assertThrows(IOException.class, () -> McpRuntime.load(json, required));
    }

    private static String config(boolean required) {
        return "{\"mcp\":{\"unavailable\":{\"type\":\"stdio\",\"command\":[\""
                + "definitely-not-a-java-agent-command" + "\"],\"required\":" + required + "}}}";
    }
}
