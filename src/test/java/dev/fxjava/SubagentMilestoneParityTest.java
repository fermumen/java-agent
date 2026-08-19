package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relevant authenticated milestone cases ported from fx core/subagent/manager.zig. */
class SubagentMilestoneParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path stateRoot;

    @Test
    void childMilestoneUsesDeclaredActiveWorkAndDeduplicatesByName() throws Exception {
        AtomicReference<SubagentTool> rootTool = new AtomicReference<>();
        List<JsonNode> childReceipts = new ArrayList<>();
        try (SubagentManager manager = new SubagentManager(json, configuration -> {
            SubagentTool childTool = rootTool.get().scoped(configuration.id());
            return prompt -> {
                childReceipts.add(call(childTool, milestone("halfway"), "shared-milestone"));
                childReceipts.add(call(childTool, milestone("halfway"), "child-milestone-2"));
                childReceipts.add(call(childTool, milestone("undeclared"), "child-undeclared"));
                return "done";
            };
        }, PermissionMode.YOLO, stateRoot)) {
            SubagentTool tool = new SubagentTool(manager);
            rootTool.set(tool);

            JsonNode rootRejected = call(tool, milestone("halfway"), "shared-milestone");
            assertFalse(rootRejected.path("ok").asBoolean());
            assertEquals("invalid_milestone_caller", rootRejected.path("error_code").asText());

            JsonNode created = call(tool, create("worker", "do work", "halfway"), "create-worker");
            String id = created.path("child_id").asText();
            JsonNode inspected = call(tool, inspect(id), "inspect-worker");
            assertEquals("completed", inspected.path("status").asText());

            assertEquals(3, childReceipts.size());
            assertTrue(childReceipts.get(0).path("ok").asBoolean());
            assertEquals("milestone_emitted", childReceipts.get(0).path("status").asText());
            assertEquals(id, childReceipts.get(0).path("child_id").asText());
            assertEquals(childReceipts.get(0).path("requested").path("event_sequence").asLong(),
                    childReceipts.get(1).path("requested").path("event_sequence").asLong());
            assertFalse(childReceipts.get(2).path("ok").asBoolean());
            assertEquals("undeclared_milestone", childReceipts.get(2).path("error_code").asText());

            long milestones = inspected.path("requested").path("events").valueStream()
                    .filter(event -> event.path("kind").asText().equals("milestone_emitted")).count();
            assertEquals(1, milestones);
            assertEquals("halfway", inspected.path("requested").path("configuration")
                    .path("notifications").path("milestones").path(0).asText());

            JsonNode replay = call(tool.scoped(id), milestone("halfway"), "shared-milestone");
            assertEquals(childReceipts.get(0), replay);

            JsonNode noWork = call(tool.scoped(id), milestone("halfway"), "idle-milestone");
            assertFalse(noWork.path("ok").asBoolean());
            assertEquals("no_active_work", noWork.path("error_code").asText());
        }
    }

    @Test
    void notificationPolicySurvivesRestart() throws Exception {
        SubagentManager.ChildFactory factory = configuration -> prompt -> "unused";
        String id;
        try (SubagentManager first = new SubagentManager(json, factory, PermissionMode.YOLO, stateRoot)) {
            SubagentTool tool = new SubagentTool(first);
            ObjectNode request = create("durable", null, "halfway");
            ((ObjectNode) request.path("command").path("create")).put("mode", "persistent");
            id = call(tool, request, "create-durable").path("child_id").asText();
        }

        try (SubagentManager restored = new SubagentManager(json, factory, PermissionMode.YOLO, stateRoot)) {
            restored.restore();
            JsonNode inspected = call(new SubagentTool(restored), inspectNow(id), "inspect-restored");
            assertEquals("halfway", inspected.path("requested").path("configuration")
                    .path("notifications").path("milestones").path(0).asText());
        }
    }

    private ObjectNode create(String name, String prompt, String milestone) {
        ObjectNode root = command("create");
        ObjectNode create = (ObjectNode) root.path("command").path("create");
        create.put("name", name).put("mode", "one_off");
        if (prompt != null) create.put("prompt", prompt);
        create.putObject("notifications").putArray("milestones").add(milestone);
        return root;
    }

    private ObjectNode milestone(String name) {
        ObjectNode root = command("message");
        ((ObjectNode) root.path("command").path("message")).putObject("milestone").put("name", name);
        return root;
    }

    private ObjectNode inspect(String id) {
        ObjectNode root = inspectNow(id);
        ((ObjectNode) root.path("command").path("inspect")).set("wait",
                json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
        return root;
    }

    private ObjectNode inspectNow(String id) {
        ObjectNode root = command("inspect");
        ObjectNode inspect = (ObjectNode) root.path("command").path("inspect");
        inspect.put("id", id).putArray("sections").add("status").add("events").add("configuration");
        return root;
    }

    private ObjectNode command(String branch) {
        ObjectNode root = json.createObjectNode();
        root.putObject("command").putObject(branch);
        return root;
    }

    private JsonNode call(SubagentTool tool, ObjectNode request, String operationId) throws Exception {
        return json.readTree(tool.execute(request, operationId));
    }
}
