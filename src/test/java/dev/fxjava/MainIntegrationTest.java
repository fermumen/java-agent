package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainIntegrationTest {
    @TempDir
    Path workspace;

    @Test
    void runsResponsesToolLoopThroughTheCli() throws Exception {
        try (FakeResponsesServer api = new FakeResponsesServer(0)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();

            int exitCode = Main.run(new String[]{
                            "--base-url", api.baseUrl(),
                            "--workspace", workspace.toString(),
                            "--yolo", "--json", "Create the smoke marker"
                    }, Map.of("OPENAI_API_KEY", "test-key",
                            "JAVA_AGENT_HOME", workspace.resolve("sessions").toString()),
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    new PrintStream(errors, true, StandardCharsets.UTF_8));

            assertEquals(0, exitCode);
            assertEquals(2, api.requestCount());
            assertEquals("created by responses smoke test\n",
                    Files.readString(workspace.resolve("smoke.txt")));
            JsonNode result = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
            assertEquals("Responses smoke test complete", result.path("output").asText());
            assertEquals("write_file", result.path("tool_calls").path(0).path("name").asText());
            assertEquals("success", result.path("tool_calls").path(0).path("status").asText());
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("YOLO enabled: permissions disabled"));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("[tool] write smoke.txt"));
        }
    }
    @Test
    void autoModeFailsClosedForMutations() throws Exception {
        try (FakeResponsesServer api = new FakeResponsesServer(0)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();

            int exitCode = Main.run(new String[]{
                            "--base-url", api.baseUrl(),
                            "--workspace", workspace.toString(),
                            "--auto", "--json", "Create the smoke marker"
                    }, Map.of("OPENAI_API_KEY", "test-key",
                            "JAVA_AGENT_HOME", workspace.resolve("auto-sessions").toString()),
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    new PrintStream(errors, true, StandardCharsets.UTF_8));

            assertEquals(0, exitCode);
            assertFalse(Files.exists(workspace.resolve("smoke.txt")));
            JsonNode result = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
            assertEquals("error", result.path("tool_calls").path(0).path("status").asText());
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("[auto-denied] write smoke.txt"));
        }
    }
}
