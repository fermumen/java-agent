package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentOperationReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void replaysIdenticalOperationsAndRejectsConflictingReuse() throws Exception {
        AtomicInteger factories = new AtomicInteger();
        try (SubagentManager manager = new SubagentManager(json, configuration -> {
            factories.incrementAndGet();
            return prompt -> "unused";
        }, PermissionMode.YOLO, temporary)) {
            SubagentTool tool = new SubagentTool(manager);
            String original = tool.execute(create("worker", false), "create-replay");
            String reordered = tool.execute(create("worker", true), "create-replay");
            assertEquals(original, reordered);
            assertEquals(1, factories.get());

            JsonNode conflict = json.readTree(tool.execute(create("different", false), "create-replay"));
            assertFalse(conflict.path("ok").asBoolean());
            assertEquals("operation_conflict", conflict.path("error_code").asText());
            assertEquals("create-replay", conflict.path("operation_id").asText());
            assertEquals(1, factories.get());
        }
    }

    @Test
    void restoresTheReplayLedgerAcrossManagerRestart() throws Exception {
        AtomicInteger factories = new AtomicInteger();
        SubagentManager.ChildFactory factory = configuration -> {
            factories.incrementAndGet();
            return prompt -> "unused";
        };
        String original;
        try (SubagentManager first = new SubagentManager(json, factory, PermissionMode.YOLO, temporary)) {
            original = new SubagentTool(first).execute(create("durable", false), "durable-create");
        }

        try (SubagentManager second = new SubagentManager(json, factory, PermissionMode.YOLO, temporary)) {
            second.restore();
            String replay = new SubagentTool(second).execute(create("durable", true), "durable-create");
            assertEquals(original, replay);
            assertEquals(2, factories.get(), "one factory for create and one for restored child");
        }
        assertTrue(Files.readString(temporary.resolve("subagents/_operations.json"), StandardCharsets.UTF_8)
                .contains("durable-create"));
    }

    @Test
    void isolatesACorruptReplayLedgerFromValidChildSnapshots() throws Exception {
        AtomicInteger factories = new AtomicInteger();
        SubagentManager.ChildFactory factory = configuration -> {
            factories.incrementAndGet();
            return prompt -> "unused";
        };
        try (SubagentManager first = new SubagentManager(json, factory, PermissionMode.YOLO, temporary)) {
            new SubagentTool(first).execute(create("kept", false), "kept-create");
        }
        Files.writeString(temporary.resolve("subagents/_operations.json"), "not-json", StandardCharsets.UTF_8);

        try (SubagentManager second = new SubagentManager(json, factory, PermissionMode.YOLO, temporary)) {
            second.restore();
            JsonNode inspected = json.readTree(new SubagentTool(second).execute(
                    inspect(firstChildId()), "inspect-after-corrupt-ledger"));
            assertTrue(inspected.path("ok").asBoolean());
        }
    }

    private String firstChildId() throws Exception {
        try (var paths = Files.list(temporary.resolve("subagents"))) {
            Path child = paths.filter(path -> path.getFileName().toString().startsWith("child-"))
                    .findFirst().orElseThrow();
            return child.getFileName().toString().replaceFirst("\\.json$", "");
        }
    }

    private ObjectNode create(String name, boolean reversedFields) {
        ObjectNode root = json.createObjectNode();
        ObjectNode create = root.putObject("command").putObject("create");
        if (reversedFields) create.put("mode", "persistent").put("name", name);
        else create.put("name", name).put("mode", "persistent");
        return root;
    }

    private ObjectNode inspect(String id) {
        ObjectNode root = json.createObjectNode();
        ObjectNode inspect = root.putObject("command").putObject("inspect");
        inspect.put("id", id).putArray("sections").add("status");
        return root;
    }
}
