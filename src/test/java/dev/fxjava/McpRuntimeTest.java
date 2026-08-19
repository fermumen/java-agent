package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void initializesDiscoversPaginatedToolsAndCallsWithDelegatedSchema() throws Exception {
        Path config = writeConfig(true);
        try (McpRuntime runtime = McpRuntime.load(json, config)) {
            List<Tool> tools = runtime.tools();
            assertEquals(List.of("mcp_search_tools", "mcp_select_tool", "mcp_features",
                            "mcp__fixture__alpha_mutate", "mcp__fixture__zeta_echo"),
                    tools.stream().map(Tool::name).toList());
            Tool mutate = tools.stream().filter(tool -> tool.name().equals("mcp__fixture__alpha_mutate")).findFirst().orElseThrow();
            Tool echo = tools.stream().filter(tool -> tool.name().equals("mcp__fixture__zeta_echo")).findFirst().orElseThrow();
            assertTrue(mutate.requiresApproval());
            assertFalse(echo.requiresApproval());
            assertFalse(echo.advertised());
            Tool select = tools.stream().filter(tool -> tool.name().equals("mcp_select_tool")).findFirst().orElseThrow();
            select.execute(json.createObjectNode().put("name", echo.name()));
            assertTrue(echo.advertised());
            assertEquals("string", echo.parameters().path("properties").path("value").path("type").asText());

            String result = echo.execute(json.createObjectNode().put("value", "hello"));
            assertTrue(result.contains("zeta.echo:hello"));
            assertTrue(result.contains("structuredContent"));
        }
    }

    @Test
    void disabledServersAreParsedWithoutStarting() throws Exception {
        try (McpRuntime runtime = McpRuntime.load(json, writeConfig(false))) {
            assertTrue(runtime.tools().isEmpty());
        }
    }

    @Test
    void missingAndEmptyConfigsProduceEmptyRuntime() throws Exception {
        try (McpRuntime missing = McpRuntime.load(json, temporary.resolve("missing.json"))) {
            assertTrue(missing.tools().isEmpty());
        }
        Path empty = temporary.resolve("empty.json");
        Files.writeString(empty, "{}");
        try (McpRuntime runtime = McpRuntime.load(json, empty)) {
            assertTrue(runtime.tools().isEmpty());
        }
    }

    @Test
    void rejectsMalformedConfigAndInsecureRemoteTransport() throws Exception {
        Path malformed = temporary.resolve("malformed.json");
        Files.writeString(malformed, "{not json");
        assertThrows(IOException.class, () -> McpRuntime.load(json, malformed));

        Path remote = temporary.resolve("remote.json");
        Files.writeString(remote, "{\"mcp\":{\"remote\":{\"type\":\"http\","
                + "\"url\":\"http://example.test/mcp\"}}}");
        IOException error = assertThrows(IOException.class, () -> McpRuntime.load(json, remote));
        assertTrue(error.getMessage().contains("HTTPS or loopback HTTP"));
    }

    @Test
    void liveReloadAtomicallyReplacesConfigAndPreservesSelectedIdentity() throws Exception {
        Path config = writeConfig(true);
        try (McpRuntime runtime = McpRuntime.load(json, config)) {
            runtime.selectTool("mcp__fixture__zeta_echo");
            Tool selected = runtime.selectedTools().get(0);

            writeConfig(config, true, 9_000);
            assertEquals(2, runtime.toolCatalog().size());
            assertEquals(List.of("mcp__fixture__zeta_echo"),
                    runtime.selectedTools().stream().map(Tool::name).toList());
            assertFalse(selected.advertised());

            writeConfig(config, false, 9_000);
            assertEquals(1, runtime.healthReport().path("disabled").asInt());
            assertTrue(runtime.toolCatalog().isEmpty());
            assertTrue(runtime.selectedTools().isEmpty());
        }
    }

    @Test
    void malformedLiveEditLeavesCurrentRuntimeAvailableForRetry() throws Exception {
        Path config = writeConfig(true);
        try (McpRuntime runtime = McpRuntime.load(json, config)) {
            assertEquals(2, runtime.toolCatalog().size());
            Files.writeString(config, "{broken");
            assertThrows(IOException.class, runtime::toolCatalog);
            writeConfig(config, true, 8_000);
            assertEquals(2, runtime.toolCatalog().size());
        }
    }

    private Path writeConfig(boolean enabled) throws Exception {
        Path config = temporary.resolve(enabled ? "enabled.json" : "disabled.json");
        writeConfig(config, enabled, 10_000);
        return config;
    }

    private void writeConfig(Path config, boolean enabled, int operationTimeout) throws Exception {
        ObjectNode root = json.createObjectNode();
        ObjectNode fixture = root.putObject("mcp").putObject("fixture");
        fixture.put("type", "stdio").put("enabled", enabled)
                .put("startup_timeout_ms", 10_000).put("operation_timeout_ms", operationTimeout);
        ArrayNode command = fixture.putArray("command");
        command.add(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
        command.add("-cp").add(System.getProperty("java.class.path")).add(FakeMcpServer.class.getName());
        Files.writeString(config, json.writeValueAsString(root));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
