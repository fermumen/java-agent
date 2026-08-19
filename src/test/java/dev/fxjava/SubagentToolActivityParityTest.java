package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubagentToolActivityParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void inspectReturnsSettledChildToolActivity() throws Exception {
        try (SubagentManager manager = new SubagentManager(json, configuration -> new SubagentManager.ChildRunner() {
            @Override public String prompt(String prompt) { return "done"; }
            @Override public List<Agent.ToolCallRecord> toolActivity() {
                return List.of(new Agent.ToolCallRecord("read", "success"),
                        new Agent.ToolCallRecord("write", "error"));
            }
        }, PermissionMode.YOLO)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode create = command("create");
            ((ObjectNode) create.path("command").path("create"))
                    .put("name", "worker").put("mode", "one_off").put("prompt", "do it");
            String id = call(tool, create, "create-activity").path("child_id").asText();

            ObjectNode inspect = command("inspect");
            ObjectNode request = (ObjectNode) inspect.path("command").path("inspect");
            request.put("id", id).putArray("sections").add("status").add("tool_activity");
            request.set("wait", json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
            JsonNode activity = call(tool, inspect, "inspect-activity")
                    .path("requested").path("tool_activity");

            assertEquals(2, activity.size());
            assertEquals("read", activity.path(0).path("name").asText());
            assertEquals("success", activity.path(0).path("status").asText());
            assertEquals("write", activity.path(1).path("name").asText());
            assertEquals("error", activity.path(1).path("status").asText());
        }
    }

    private ObjectNode command(String branch) {
        ObjectNode root = json.createObjectNode();
        root.putObject("command").putObject(branch);
        return root;
    }

    private JsonNode call(SubagentTool tool, ObjectNode input, String callId) throws Exception {
        return json.readTree(tool.execute(input, callId));
    }
}
