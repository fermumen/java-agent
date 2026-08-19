package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentPaginationParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void messagePagesUseGenerationBoundV1CursorAndRejectStaleReuse() throws Exception {
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "answer:" + prompt, PermissionMode.YOLO)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode create = command("create");
            ((ObjectNode) create.path("command").path("create")).put("name", "worker")
                    .put("mode", "persistent");
            String id = call(tool, create, "create").path("child_id").asText();
            send(tool, id, "one", "send-1");
            send(tool, id, "two", "send-2");

            ObjectNode settled = inspect(id, 100, null);
            ((ObjectNode) settled.path("command").path("inspect")).set("wait",
                    json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
            call(tool, settled, "settle");

            JsonNode first = call(tool, inspect(id, 2, null), "page-1");
            assertEquals(2, first.path("requested").path("messages").size());
            String cursor = first.path("cursor").asText();
            assertTrue(cursor.matches("v1:[0-9]+:2"));
            JsonNode second = call(tool, inspect(id, 2, cursor), "page-2");
            assertTrue(second.path("requested").path("messages").size() > 0);

            send(tool, id, "three", "send-3");
            JsonNode stale = call(tool, inspect(id, 2, cursor), "stale");
            assertFalse(stale.path("ok").asBoolean());
            assertEquals("stale_cursor", stale.path("error_code").asText());
        }
    }

    private void send(SubagentTool tool, String id, String content, String callId) throws Exception {
        ObjectNode root = command("message");
        ((ObjectNode) root.path("command").path("message")).set("send",
                json.createObjectNode().put("id", id).put("content", content));
        call(tool, root, callId);
    }

    private ObjectNode inspect(String id, int limit, String cursor) {
        ObjectNode root = command("inspect");
        ObjectNode inspect = (ObjectNode) root.path("command").path("inspect");
        inspect.put("id", id).put("limit", limit).putArray("sections").add("messages").add("status");
        if (cursor != null) inspect.put("cursor", cursor);
        return root;
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
