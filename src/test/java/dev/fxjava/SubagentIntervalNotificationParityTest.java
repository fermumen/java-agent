package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic interval-policy cases ported from fx subagent manager tests. */
class SubagentIntervalNotificationParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path stateRoot;

    @Test
    void pollingCoalescesTicksAndDurationStopsBeforeOverdueEmission() throws Exception {
        AtomicLong now = new AtomicLong();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SubagentManager manager = new SubagentManager(json, configuration -> prompt -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "done";
        }, PermissionMode.YOLO, null, now::get)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode request = create("worker", "persistent", "work");
            ((ObjectNode) request.path("command").path("create")).putObject("notifications")
                    .put("report_interval_ms", 100).put("report_duration_ms", 350);
            call(tool, request, "create");
            assertTrue(running.await(2, TimeUnit.SECONDS));

            now.set(99);
            assertNull(manager.prepareParentContext("root"));
            now.set(100);
            Agent.PreparedParentContext first = manager.prepareParentContext("root");
            assertTrue(first.content().contains("\"kind\":\"interval\""));
            assertTrue(first.content().contains("\"coalesced_ticks\":1"));
            manager.acknowledgeParentContext(first);

            now.set(320);
            Agent.PreparedParentContext coalesced = manager.prepareParentContext("root");
            assertTrue(coalesced.content().contains("\"coalesced_ticks\":2"));
            manager.acknowledgeParentContext(coalesced);

            now.set(350);
            assertNull(manager.prepareParentContext("root"));
            now.set(900);
            assertNull(manager.prepareParentContext("root"));
            release.countDown();
        }
    }

    @Test
    void terminalStopIsIndependentFromTerminalPayloadSelection() throws Exception {
        AtomicLong now = new AtomicLong();
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "done", PermissionMode.YOLO, null, now::get)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode request = create("worker", "one_off", "work");
            ObjectNode notifications = ((ObjectNode) request.path("command").path("create"))
                    .putObject("notifications");
            notifications.put("report_interval_ms", 100);
            notifications.putObject("terminal").put("completed", false);
            String id = call(tool, request, "create").path("child_id").asText();
            waitFor(tool, id);

            now.set(100);
            assertNull(manager.prepareParentContext("root"));
        }
    }

    @Test
    void removingTerminalStopAllowsReportsOfSettledState() throws Exception {
        AtomicLong now = new AtomicLong();
        try (SubagentManager manager = new SubagentManager(json,
                configuration -> prompt -> "done", PermissionMode.YOLO, null, now::get)) {
            SubagentTool tool = new SubagentTool(manager);
            ObjectNode request = create("worker", "one_off", "work");
            ObjectNode notifications = ((ObjectNode) request.path("command").path("create"))
                    .putObject("notifications").put("report_interval_ms", 100);
            notifications.putArray("stop_conditions");
            String id = call(tool, request, "create").path("child_id").asText();
            waitFor(tool, id);

            now.set(100);
            Agent.PreparedParentContext prepared = manager.prepareParentContext("root");
            assertTrue(prepared.content().contains("\"kind\":\"terminal\""));
            assertTrue(prepared.content().contains("\"kind\":\"interval\""));
            assertTrue(prepared.content().contains("\"state\":\"completed\""));
            manager.acknowledgeParentContext(prepared);
            now.set(300);
            assertTrue(manager.prepareParentContext("root").content()
                    .contains("\"coalesced_ticks\":2"));
        }
    }

    @Test
    void intervalTickAndDeliveryCursorsSurviveRestart() throws Exception {
        AtomicLong now = new AtomicLong();
        SubagentManager.ChildFactory factory = configuration -> prompt -> "done";
        ObjectNode request = create("worker", "one_off", "work");
        ObjectNode notifications = ((ObjectNode) request.path("command").path("create"))
                .putObject("notifications").put("report_interval_ms", 100);
        notifications.putArray("stop_conditions");
        try (SubagentManager first = new SubagentManager(
                json, factory, PermissionMode.YOLO, stateRoot, now::get)) {
            SubagentTool tool = new SubagentTool(first);
            String id = call(tool, request, "create").path("child_id").asText();
            waitFor(tool, id);
            now.set(100);
            Agent.PreparedParentContext prepared = first.prepareParentContext("root");
            first.acknowledgeParentContext(prepared);
        }

        try (SubagentManager restored = new SubagentManager(
                json, factory, PermissionMode.YOLO, stateRoot, now::get)) {
            restored.restore();
            now.set(300);
            Agent.PreparedParentContext prepared = restored.prepareParentContext("root");
            assertTrue(prepared.content().contains("\"kind\":\"interval\""));
            assertTrue(prepared.content().contains("\"coalesced_ticks\":2"));
            assertTrue(!prepared.content().contains("\"kind\":\"terminal\""));
        }
    }

    private ObjectNode create(String name, String mode, String prompt) {
        ObjectNode root = json.createObjectNode();
        ObjectNode create = root.putObject("command").putObject("create");
        create.put("name", name).put("mode", mode).put("prompt", prompt);
        return root;
    }

    private void waitFor(SubagentTool tool, String id) throws Exception {
        ObjectNode root = json.createObjectNode();
        ObjectNode inspect = root.putObject("command").putObject("inspect");
        inspect.put("id", id).putArray("sections").add("status");
        inspect.set("wait", json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
        assertEquals("completed", call(tool, root, "wait-" + id).path("status").asText());
    }

    private JsonNode call(SubagentTool tool, ObjectNode request, String operation) throws Exception {
        return json.readTree(tool.execute(request, operation));
    }
}
