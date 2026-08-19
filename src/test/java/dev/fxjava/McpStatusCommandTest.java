package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStatusCommandTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void listReportsReadyDisabledAndRequiredFailureWithoutAuthentication() throws Exception {
        Path config = writeConfig(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int code = Main.run(new String[]{"mcp", "list", "--json", "--mcp-config", config.toString()},
                Map.of(), new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        JsonNode report = json.readTree(output.toString(StandardCharsets.UTF_8));
        assertEquals("mcp", report.path("kind").asText());
        assertEquals(3, report.path("count").asInt());
        assertEquals(1, report.path("ready").asInt());
        assertEquals(1, report.path("failed").asInt());
        assertEquals(1, report.path("disabled").asInt());
        JsonNode ready = report.path("servers").path(0);
        assertEquals("2025-06-18", ready.path("protocol_version").asText());
        assertEquals(2, ready.path("tools").asInt());
        assertEquals("unavailable", ready.path("listener").asText());
        assertTrue(report.path("servers").path(2).path("required").asBoolean());
        assertEquals("failed", report.path("servers").path(2).path("connection").asText());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("super-secret"));
    }

    @Test
    void humanAndInteractiveReportsShareTheCompactHealthView() throws Exception {
        Path config = writeConfig(false);
        ByteArrayOutputStream commandOutput = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"mcp", "list", "--mcp-config", config.toString()}, Map.of(),
                new PrintStream(commandOutput, true, StandardCharsets.UTF_8), System.err));
        String human = commandOutput.toString(StandardCharsets.UTF_8);
        assertTrue(human.contains("MCP health (3 servers):"));
        assertTrue(human.contains("ready connection=ready"));
        assertTrue(human.contains("failed connection=failed"));

        ByteArrayOutputStream interactiveOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"--no-save", "--workspace", temporary.toString(),
                        "--mcp-config", config.toString()}, Map.of("OPENAI_API_KEY", "test-key"),
                new ByteArrayInputStream("/mcp list\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(interactiveOutput, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        assertTrue(interactiveOutput.toString(StandardCharsets.UTF_8).contains("MCP health (3 servers):"));
    }

    private Path writeConfig(boolean failedIsRequired) throws Exception {
        ObjectNode root = json.createObjectNode();
        ObjectNode servers = root.putObject("mcp");
        ObjectNode ready = servers.putObject("ready").put("type", "stdio")
                .put("startup_timeout_ms", 10_000).put("operation_timeout_ms", 10_000);
        ready.putObject("headers").put("X-Secret", "super-secret");
        ArrayNode command = ready.putArray("command");
        command.add(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
        command.add("-cp").add(System.getProperty("java.class.path")).add(FakeMcpServer.class.getName());
        servers.putObject("disabled").put("type", "stdio").put("enabled", false)
                .putArray("command").add("not-started");
        servers.putObject("failed").put("type", "stdio").put("required", failedIsRequired)
                .putArray("command").add(temporary.resolve("missing-executable").toString());
        Path config = temporary.resolve(failedIsRequired ? "required.json" : "optional.json");
        Files.writeString(config, json.writeValueAsString(root));
        return config;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
