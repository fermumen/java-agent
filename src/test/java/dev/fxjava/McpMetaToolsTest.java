package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McpMetaToolsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void searchAndExactSelectionExposeBoundedMetadata() throws Exception {
        try (McpRuntime runtime = runtime()) {
            Tool search = tool(runtime, "mcp_search_tools");
            String found = search.execute(json.createObjectNode().put("query", "echo").put("limit", 1));
            assertTrue(found.contains("mcp__fixture__zeta_echo"));
            assertTrue(found.contains("input_schema"));

            Tool select = tool(runtime, "mcp_select_tool");
            String selected = select.execute(json.createObjectNode().put("name", "mcp__fixture__zeta_echo"));
            assertTrue(selected.contains("\"advertised\":true"));
            assertThrows(Exception.class, () -> select.execute(json.createObjectNode().put("name", "unknown")));
        }
    }

    @Test
    void resourcesPromptsAndCompletionUseExactServerAndPaginate() throws Exception {
        try (McpRuntime runtime = runtime()) {
            Tool features = tool(runtime, "mcp_features");
            assertTrue(execute(features, "resource_list").contains("fixture:///one"));
            assertTrue(execute(features, "resource_list").contains("fixture:///two"));
            assertTrue(execute(features, "resource_templates").contains("fixture:///{name}"));
            assertTrue(features.execute(base("resource_read").put("uri", "fixture:///one"))
                    .contains("subscriptions=0"));
            assertTrue(features.execute(base("resource_read").put("uri", "fixture:///one"))
                    .contains("subscriptions=1"));
            assertEquals(1, runtime.healthReport().path("servers").path(0)
                    .path("resource_subscriptions").asInt());
            assertEquals(1, runtime.healthReport().path("servers").path(0)
                    .path("resource_updates").asInt());
            assertTrue(execute(features, "prompt_list").contains("review"));
            ObjectNode promptGet = base("prompt_get").put("prompt", "review");
            promptGet.putObject("arguments").put("scope", "changes");
            assertTrue(features.execute(promptGet).contains("review it"));
            assertTrue(features.execute(base("prompt_complete").put("prompt", "review")
                    .put("argument", "scope").put("value", "a")).contains("alpha"));
            assertTrue(features.execute(base("resource_complete").put("uri_template", "fixture:///{name}")
                    .put("argument", "name").put("value", "b")).contains("beta"));
        }
    }

    private String execute(Tool tool, String action) throws Exception {
        return tool.execute(base(action));
    }

    private ObjectNode base(String action) {
        return json.createObjectNode().put("action", action).put("server", "fixture");
    }

    private Tool tool(McpRuntime runtime, String name) {
        return runtime.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private McpRuntime runtime() throws Exception {
        ObjectNode root = json.createObjectNode();
        ArrayNode command = root.putObject("mcp").putObject("fixture").put("type", "stdio")
                .putArray("command");
        command.add(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
        command.add("-cp").add(System.getProperty("java.class.path")).add(FakeMcpServer.class.getName());
        Path config = temporary.resolve("mcp.json");
        Files.writeString(config, json.writeValueAsString(root));
        return McpRuntime.load(json, config);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
