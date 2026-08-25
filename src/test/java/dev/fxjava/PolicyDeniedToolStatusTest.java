package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyDeniedToolStatusTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void deniedTerminalAndSubagentCallsRecordAndListenAsErrors() throws Exception {
        Tool terminal = new TerminalTool(new WorkspaceTools.Workspace(workspace), null);
        assertDeniedError(terminal, "{\"action\":\"exec\",\"command\":\"true\"}");

        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "unused", PermissionMode.ASK)) {
            Tool subagent = new SubagentTool(manager);
            assertDeniedError(subagent, "{\"command\":{\"create\":{\"name\":\"child\",\"mode\":\"one_off\"}}}");
        }
    }

    private void assertDeniedError(Tool tool, String arguments) throws Exception {
        FakeClient client = new FakeClient(toolResponse(tool.name(), arguments), textResponse());
        ApprovalPolicy deny = new ApprovalPolicy() {
            @Override public boolean approve(Tool ignored, JsonNode value) { return true; }
            @Override public boolean preflightDeny(Tool ignored, JsonNode value) { return true; }
        };
        Agent agent = new Agent(json, client, List.of(tool), deny,
                new PrintStream(PrintStream.nullOutputStream()), 3, "instructions");
        AtomicBoolean listenerError = new AtomicBoolean();

        agent.prompt("test", ignored -> { }, new Agent.TurnListener() {
            @Override public void onToolStart(String name, String preview) { }
            @Override public void onToolEnd(String name, boolean error) { listenerError.set(error); }
        });

        assertEquals(List.of(new Agent.ToolCallRecord(tool.name(), "error")), agent.lastToolCalls());
        assertTrue(listenerError.get());
        assertEquals("Error: user denied this tool call",
                client.inputs.get(1).path(3).path("output").asText());
    }

    private ObjectNode toolResponse(String name, String arguments) {
        ObjectNode response = completed();
        ArrayNode output = response.putArray("output");
        output.addObject().put("type", "reasoning").put("encrypted_content", "state").putArray("summary");
        output.addObject().put("type", "function_call").put("call_id", "call-1")
                .put("name", name).put("arguments", arguments).put("status", "completed");
        return response;
    }

    private ObjectNode textResponse() {
        ObjectNode response = completed();
        response.putArray("output").addObject().put("type", "message").put("role", "assistant")
                .put("status", "completed").putArray("content")
                .addObject().put("type", "output_text").put("text", "done").putArray("annotations");
        return response;
    }

    private ObjectNode completed() {
        return json.createObjectNode().put("id", "response").put("object", "response").put("status", "completed");
    }

    private static final class FakeClient implements ResponsesClient {
        private final Queue<ObjectNode> responses = new ArrayDeque<>();
        private final java.util.ArrayList<ArrayNode> inputs = new java.util.ArrayList<>();

        FakeClient(ObjectNode... responses) { this.responses.addAll(List.of(responses)); }

        @Override public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            inputs.add(input.deepCopy());
            return responses.remove();
        }
    }
}
