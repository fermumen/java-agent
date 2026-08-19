package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable parent-delivery cases ported from fx parent_delivery_projector.zig. */
class SubagentParentDeliveryParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path stateRoot;

    @Test
    void terminalDeliveryReplaysUntilAcknowledgedAndSurvivesRestart() throws Exception {
        String childId;
        Agent.PreparedParentContext prepared;
        SubagentManager.ChildFactory factory = configuration -> prompt -> "done";
        try (SubagentManager manager = new SubagentManager(json, factory, PermissionMode.YOLO, stateRoot)) {
            SubagentTool tool = new SubagentTool(manager);
            childId = call(tool, create("worker", "one_off", "finish"), "create").path("child_id").asText();
            waitFor(tool, childId);

            prepared = manager.prepareParentContext("root");
            assertTrue(prepared.content().contains("<subagent_deliveries trusted_runtime_context=\"true\">"));
            assertTrue(prepared.content().contains("\"terminal\":\"completed\""));
            assertEquals(prepared, manager.prepareParentContext("root"));
        }

        try (SubagentManager restored = new SubagentManager(json, factory, PermissionMode.YOLO, stateRoot)) {
            restored.restore();
            assertEquals(prepared, restored.prepareParentContext("root"));
            restored.acknowledgeParentContext(prepared);
            restored.acknowledgeParentContext(prepared);
            assertNull(restored.prepareParentContext("root"));
        }
    }

    @Test
    void milestoneAndTerminalAreOrderedAndDeliveredOnlyToCurrentDirectParent() throws Exception {
        AtomicReference<SubagentTool> tool = new AtomicReference<>();
        try (SubagentManager manager = new SubagentManager(json, configuration -> prompt -> {
            tool.get().scoped(configuration.id()).execute(milestone("halfway"), "milestone");
            return "evidence";
        }, PermissionMode.YOLO)) {
            tool.set(new SubagentTool(manager));
            ObjectNode create = create("worker", "persistent", "investigate");
            ((ObjectNode) create.path("command").path("create"))
                    .putObject("notifications").putArray("milestones").add("halfway");
            String childId = call(tool.get(), create, "create").path("child_id").asText();
            waitFor(tool.get(), childId);

            Agent.PreparedParentContext root = manager.prepareParentContext("root");
            assertTrue(root.content().contains("\"kind\":\"milestone\""));
            assertTrue(root.content().contains("\"name\":\"halfway\""));
            assertTrue(root.content().indexOf("\"kind\":\"milestone\"")
                    < root.content().indexOf("\"kind\":\"terminal\""));

            call(tool.get(), relationship(childId, "detach", null), "detach");
            assertNull(manager.prepareParentContext("root"));
            call(tool.get(), relationship(childId, "attach", null), "attach");
            assertEquals(root, manager.prepareParentContext("root"));
        }
    }

    @Test
    void nestedParentReceivesGrandchildDeliveryButRootDoesNot() throws Exception {
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "answer", PermissionMode.YOLO)) {
            SubagentTool tool = new SubagentTool(manager);
            String parent = call(tool, create("parent", "persistent", null), "parent").path("child_id").asText();
            String grandchild = call(tool, create("grandchild", "persistent", null), "grandchild")
                    .path("child_id").asText();
            call(tool, relationship(grandchild, "reparent", parent), "reparent");
            call(tool, message(grandchild, "work"), "send");
            waitFor(tool, grandchild);

            assertNull(manager.prepareParentContext("root"));
            Agent.PreparedParentContext nested = manager.prepareParentContext(parent);
            assertTrue(nested.content().contains(grandchild));
            assertTrue(nested.content().contains("\"terminal\":\"completed\""));
        }
    }

    @Test
    void disabledTerminalPolicyDoesNotProject() throws Exception {
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "answer", PermissionMode.YOLO)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode request = create("quiet", "one_off", "work");
            ((ObjectNode) request.path("command").path("create")).putObject("notifications")
                    .putObject("terminal").put("completed", false);
            String childId = call(tool, request, "create").path("child_id").asText();
            waitFor(tool, childId);
            assertNull(manager.prepareParentContext("root"));
        }
    }

    private void waitFor(SubagentTool tool, String id) throws Exception {
        ObjectNode root = command("inspect");
        ObjectNode inspect = (ObjectNode) root.path("command").path("inspect");
        inspect.put("id", id).putArray("sections").add("status");
        inspect.set("wait", json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
        JsonNode result = call(tool, root, "wait-" + id);
        assertFalse(result.path("requested").path("status").path("state").asText().equals("running"));
    }

    private ObjectNode create(String name, String mode, String prompt) {
        ObjectNode root = command("create");
        ObjectNode create = (ObjectNode) root.path("command").path("create");
        create.put("name", name).put("mode", mode);
        if (prompt != null) create.put("prompt", prompt);
        return root;
    }

    private ObjectNode message(String id, String content) {
        ObjectNode root = command("message");
        ((ObjectNode) root.path("command").path("message")).putObject("send")
                .put("id", id).put("content", content);
        return root;
    }

    private ObjectNode milestone(String name) {
        ObjectNode root = command("message");
        ((ObjectNode) root.path("command").path("message")).putObject("milestone").put("name", name);
        return root;
    }

    private ObjectNode relationship(String id, String action, String parentId) {
        ObjectNode root = command("relationship");
        ObjectNode relationship = (ObjectNode) root.path("command").path("relationship");
        relationship.put("id", id).put("action", action);
        if (parentId != null) relationship.put("parent_id", parentId);
        return root;
    }

    private ObjectNode command(String branch) {
        ObjectNode root = json.createObjectNode();
        root.putObject("command").putObject(branch);
        return root;
    }

    private JsonNode call(SubagentTool tool, ObjectNode input, String operation) throws Exception {
        return json.readTree(tool.execute(input, operation));
    }
}
