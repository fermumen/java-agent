package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentDynamicApprovalTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void perCallApprovalCanGateAReadOnlyToolBeforeExecution() throws Exception {
        Queue<ObjectNode> responses = new ArrayDeque<>();
        responses.add(toolResponse());
        responses.add(textResponse());
        ResponsesClient client = (input, tools, instructions) -> responses.remove();
        AtomicBoolean executed = new AtomicBoolean();
        Tool externalRead = new Tool() {
            @Override public String name() { return "external_read"; }
            @Override public String description() { return "Read an external path"; }
            @Override public ObjectNode parameters() {
                return json.createObjectNode().put("type", "object");
            }
            @Override public boolean requiresApproval() { return false; }
            @Override public boolean requiresApproval(JsonNode arguments) { return true; }
            @Override public String preview(JsonNode arguments) { return "read outside"; }
            @Override public String execute(JsonNode arguments) {
                executed.set(true);
                return "unsafe";
            }
        };
        Agent agent = new Agent(json, client, List.of(externalRead), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 3, "system");

        assertEquals("Denied", agent.prompt("read it"));
        assertFalse(executed.get());
    }

    private ObjectNode toolResponse() {
        ObjectNode response = json.createObjectNode().put("status", "completed");
        response.putArray("output").addObject().put("type", "function_call")
                .put("call_id", "call_external").put("name", "external_read")
                .put("arguments", "{\"path\":\"C:\\\\outside.txt\"}");
        return response;
    }

    private ObjectNode textResponse() {
        ObjectNode response = json.createObjectNode().put("status", "completed");
        response.putArray("output").addObject().put("type", "message").putArray("content")
                .addObject().put("type", "output_text").put("text", "Denied");
        return response;
    }
}
