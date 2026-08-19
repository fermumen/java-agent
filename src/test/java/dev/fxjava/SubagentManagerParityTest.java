package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentManagerParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void oneOffCreationIsAsynchronousAndInspectWaitReturnsSettledMessages() throws Exception {
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "completed: " + prompt, PermissionMode.YOLO)) {
            SubagentTool tool = new SubagentTool(manager);
            JsonNode created = call(tool, create("worker", "one_off", "do it"), "call-create");
            assertTrue(created.path("ok").asBoolean());
            assertEquals("call-create", created.path("operation_id").asText());
            assertEquals("created", created.path("status").asText());
            String id = created.path("child_id").asText();
            assertFalse(id.isBlank());

            JsonNode inspected = call(tool, inspect(id, true, "status", "messages", "events"), "call-inspect");
            assertEquals("completed", inspected.path("status").asText());
            assertEquals("completed", inspected.path("requested").path("status").path("state").asText());
            assertEquals("user", inspected.path("requested").path("messages").path(0).path("role").asText());
            assertEquals("assistant", inspected.path("requested").path("messages").path(1).path("role").asText());
            assertEquals("completed: do it", inspected.path("requested").path("messages").path(1)
                    .path("content").asText());
        }
    }

    @Test
    void persistentMessageConfigurationRelationshipAndLifecycleUseStableReceipts() throws Exception {
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "answer:" + prompt, PermissionMode.ASK)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode request = create("persistent", "persistent", null);
            request.path("command").path("create");
            ((ObjectNode) request.path("command").path("create")).put("permission_mode", "yolo");
            JsonNode created = call(tool, request, "create-1");
            String id = created.path("child_id").asText();

            JsonNode configuration = call(tool, inspect(id, false, "configuration", "status"), "inspect-config");
            assertEquals("ask", configuration.path("requested").path("configuration")
                    .path("permission_mode").asText());

            ObjectNode send = command("message");
            ((ObjectNode) send.path("command").path("message")).set("send",
                    json.createObjectNode().put("id", id).put("content", "next"));
            assertEquals("message_queued", call(tool, send, "send-1").path("status").asText());
            JsonNode settled = call(tool, inspect(id, true, "status", "messages"), "inspect-settled");
            assertEquals("idle", settled.path("status").asText());
            assertTrue(settled.path("requested").path("messages").toString().contains("answer:next"));

            ObjectNode configure = command("configure");
            ((ObjectNode) configure.path("command").path("configure")).put("id", id).put("name", "renamed");
            assertEquals("configured", call(tool, configure, "configure-1").path("status").asText());

            ObjectNode close = lifecycle(id, "close");
            assertEquals("lifecycle_changed", call(tool, close, "close-1").path("status").asText());
            assertEquals("archived", call(tool, inspect(id, false, "status"), "inspect-archived")
                    .path("requested").path("status").path("state").asText());
            call(tool, lifecycle(id, "reopen"), "reopen-1");
            assertEquals("idle", call(tool, inspect(id, false, "status"), "inspect-reopened")
                    .path("requested").path("status").path("state").asText());
        }
    }

    @Test
    void cancellationAndRejectedCommandsReturnFxStructuredEnvelopes() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        try (SubagentManager manager = new SubagentManager(json, configuration -> prompt -> {
            running.countDown();
            Thread.sleep(30_000);
            return "late";
        }, PermissionMode.YOLO)) {
            SubagentTool tool = new SubagentTool(manager);
            JsonNode created = call(tool, create("slow", "one_off", "wait"), "create-slow");
            String id = created.path("child_id").asText();
            assertTrue(running.await(2, TimeUnit.SECONDS));
            JsonNode cancelled = call(tool, lifecycle(id, "cancel"), "cancel-1");
            assertTrue(cancelled.path("ok").asBoolean());
            assertEquals("cancelled", call(tool, inspect(id, false, "status"), "inspect-cancelled")
                    .path("requested").path("status").path("state").asText());

            JsonNode rejected = call(tool, json.createObjectNode().set("command", json.createObjectNode()), "bad-call");
            assertFalse(rejected.path("ok").asBoolean());
            assertEquals("invalid_branch_selection", rejected.path("error_code").asText());
            assertEquals("bad-call", rejected.path("operation_id").asText());
            assertTrue(tool.isErrorResult(rejected.toString()));
        }
    }

    @Test
    void schemaAdvertisesAllSixCanonicalBranches() {
        SubagentManager manager = new SubagentManager(json, configuration -> prompt -> "", PermissionMode.YOLO);
        try {
            JsonNode branches = new SubagentTool(manager).parameters().path("properties").path("command")
                    .path("properties");
            for (String branch : new String[]{"create", "inspect", "message", "relationship", "configure", "lifecycle"}) {
                assertNotNull(branches.get(branch));
            }
        } finally {
            manager.close();
        }
    }

    private ObjectNode create(String name, String mode, String prompt) {
        ObjectNode root = command("create");
        ObjectNode create = (ObjectNode) root.path("command").path("create");
        create.put("name", name).put("mode", mode);
        if (prompt != null) create.put("prompt", prompt);
        return root;
    }

    private ObjectNode inspect(String id, boolean wait, String... sections) {
        ObjectNode root = command("inspect");
        ObjectNode inspect = (ObjectNode) root.path("command").path("inspect");
        inspect.put("id", id);
        ArrayNode values = inspect.putArray("sections");
        for (String section : sections) values.add(section);
        if (wait) inspect.set("wait", json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
        return root;
    }

    private ObjectNode lifecycle(String id, String action) {
        ObjectNode root = command("lifecycle");
        ((ObjectNode) root.path("command").path("lifecycle")).put("id", id).put("action", action);
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
