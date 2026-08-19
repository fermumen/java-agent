package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolResultIntegrationTest {
    private static final Pattern HANDLE = Pattern.compile("<tool_result_handle>([^<]+)</tool_result_handle>");
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void agentStoresLargeOutputThenModelReadsItByLiteralQuery() throws Exception {
        ToolResultStore store = new ToolResultStore(root);
        DynamicClient client = new DynamicClient();
        Agent agent = new Agent(json, client, List.of(new LargeTool(), new ReadToolResultTool(store)),
                (tool, arguments) -> true, new PrintStream(new ByteArrayOutputStream()), 5, "system", store);
        agent.setToolResultSession("session-1");

        assertEquals("Recovered full result", agent.prompt("find needle"));
        assertTrue(client.previewBytes < 8_000);
        assertTrue(client.previewRedacted);
        assertTrue(client.queryOutput.contains("needle from full result"));
    }

    private final class DynamicClient implements ResponsesClient {
        int calls;
        int previewBytes;
        boolean previewRedacted;
        String queryOutput;

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            calls++;
            if (calls == 1) return function("call-large", "large", "{}");
            if (calls == 2) {
                String preview = input.path(input.size() - 1).path("output").asText();
                previewBytes = preview.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                previewRedacted = preview.contains("API_KEY=[redacted]") && !preview.contains("secret-value");
                Matcher matcher = HANDLE.matcher(preview);
                assertTrue(matcher.find());
                return function("call-read", "read_tool_result", json.createObjectNode()
                        .put("handle", matcher.group(1)).put("query", "needle").toString());
            }
            queryOutput = input.path(input.size() - 1).path("output").asText();
            return text("Recovered full result");
        }
    }

    private ObjectNode function(String callId, String name, String arguments) {
        ObjectNode response = json.createObjectNode().put("status", "completed");
        response.putArray("output").addObject().put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments);
        return response;
    }

    private ObjectNode text(String value) {
        ObjectNode response = json.createObjectNode().put("status", "completed");
        response.putArray("output").addObject().put("type", "message").put("role", "assistant")
                .putArray("content").addObject().put("type", "output_text").put("text", value);
        return response;
    }

    private final class LargeTool implements Tool {
        @Override public String name() { return "large"; }
        @Override public String description() { return "Return a large test value"; }
        @Override public ObjectNode parameters() {
            ObjectNode schema = json.createObjectNode().put("type", "object");
            schema.putObject("properties");
            return schema;
        }
        @Override public boolean requiresApproval() { return false; }
        @Override public String preview(JsonNode arguments) { return "large"; }
        @Override public String execute(JsonNode arguments) {
            return "API_KEY=secret-value\nneedle from full result\n" + "x".repeat(20_000);
        }
    }
}
