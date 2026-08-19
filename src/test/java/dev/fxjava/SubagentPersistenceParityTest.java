package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentPersistenceParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path stateRoot;

    @Test
    void settledChildConfigurationMessagesAndConversationRestore() throws Exception {
        StatefulFactory firstFactory = new StatefulFactory(false);
        String id;
        try (SubagentManager first = new SubagentManager(json, firstFactory, PermissionMode.YOLO, stateRoot)) {
            SubagentTool tool = new SubagentTool(first);
            id = call(tool, create("worker", "persistent", "first"), "create").path("child_id").asText();
            call(tool, inspect(id, true), "settle");
            ObjectNode configure = command("configure");
            ((ObjectNode) configure.path("command").path("configure")).put("id", id)
                    .put("name", "renamed").put("model", "model-2");
            call(tool, configure, "configure");
        }

        StatefulFactory restoredFactory = new StatefulFactory(false);
        try (SubagentManager restored = new SubagentManager(json, restoredFactory, PermissionMode.YOLO, stateRoot)) {
            restored.restore();
            SubagentTool tool = new SubagentTool(restored);
            JsonNode inspection = call(tool, inspect(id, false), "inspect-restored").path("requested");
            assertEquals("idle", inspection.path("status").path("state").asText());
            assertEquals("renamed", inspection.path("configuration").path("name").asText());
            assertEquals("model-2", inspection.path("configuration").path("model").asText());
            assertTrue(inspection.path("messages").toString().contains("answer:first"));
            assertEquals(List.of("first"), restoredFactory.runners.get(0).restoredPrompts);
        }
    }

    @Test
    void runningChildRestoresInterruptedAndOnlyResumesExplicitly() throws Exception {
        StatefulFactory blocking = new StatefulFactory(true);
        String id;
        SubagentManager first = new SubagentManager(json, blocking, PermissionMode.YOLO, stateRoot);
        SubagentTool firstTool = new SubagentTool(first);
        id = call(firstTool, create("slow", "persistent", "unfinished"), "create-slow")
                .path("child_id").asText();
        assertTrue(blocking.started.await(2, TimeUnit.SECONDS));
        first.close();

        StatefulFactory resumedFactory = new StatefulFactory(false);
        try (SubagentManager restored = new SubagentManager(json, resumedFactory, PermissionMode.YOLO, stateRoot)) {
            restored.restore();
            SubagentTool tool = new SubagentTool(restored);
            assertEquals("interrupted", call(tool, inspect(id, false), "inspect-interrupted")
                    .path("requested").path("status").path("state").asText());
            assertEquals(0, resumedFactory.runners.get(0).executions.size());
            call(tool, lifecycle(id, "resume"), "resume");
            JsonNode settled = call(tool, inspect(id, true), "settled");
            assertEquals("idle", settled.path("requested").path("status").path("state").asText());
            assertEquals(List.of("unfinished"), resumedFactory.runners.get(0).executions);
        }
    }

    private ObjectNode create(String name, String mode, String prompt) {
        ObjectNode root = command("create");
        ObjectNode create = (ObjectNode) root.path("command").path("create");
        create.put("name", name).put("mode", mode);
        if (prompt != null) create.put("prompt", prompt);
        return root;
    }

    private ObjectNode inspect(String id, boolean wait) {
        ObjectNode root = command("inspect");
        ObjectNode inspect = (ObjectNode) root.path("command").path("inspect");
        inspect.put("id", id).putArray("sections").add("status").add("messages").add("configuration");
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

    private final class StatefulFactory implements SubagentManager.ChildFactory {
        final boolean block;
        final CountDownLatch started = new CountDownLatch(1);
        final List<StatefulRunner> runners = new ArrayList<>();

        StatefulFactory(boolean block) { this.block = block; }

        @Override public SubagentManager.ChildRunner create(SubagentManager.ChildConfiguration configuration) {
            StatefulRunner runner = new StatefulRunner(block, started);
            runners.add(runner);
            return runner;
        }
    }

    private final class StatefulRunner implements SubagentManager.ChildRunner {
        final boolean block;
        final CountDownLatch started;
        final List<String> prompts = new ArrayList<>();
        final List<String> restoredPrompts = new ArrayList<>();
        final List<String> executions = new ArrayList<>();

        StatefulRunner(boolean block, CountDownLatch started) {
            this.block = block;
            this.started = started;
        }

        @Override public String prompt(String prompt) throws Exception {
            executions.add(prompt);
            prompts.add(prompt);
            started.countDown();
            if (block) Thread.sleep(30_000);
            return "answer:" + prompt;
        }

        @Override public ObjectNode snapshot() {
            ObjectNode value = json.createObjectNode();
            ArrayNode saved = value.putArray("prompts");
            prompts.forEach(saved::add);
            return value;
        }

        @Override public void restore(ObjectNode snapshot) {
            snapshot.path("prompts").forEach(value -> restoredPrompts.add(value.asText()));
            prompts.addAll(restoredPrompts);
        }
    }
}
