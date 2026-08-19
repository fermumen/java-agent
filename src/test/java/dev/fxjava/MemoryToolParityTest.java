package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path stateRoot;

    @Test
    void portsFxSaveDeduplicateListAndClearOutput() throws Exception {
        Tool memory = new MemoryTool(stateRoot);
        assertEquals("No saved memories", memory.execute(args("list", null)));
        assertEquals("no fact provided", memory.execute(args("save", null)));
        assertEquals("remembered", memory.execute(args("save", "likes Java")));
        assertEquals("remembered", memory.execute(args("save", "likes Java")));
        assertEquals("- likes Java\n", memory.execute(args("list", null)));
        assertEquals(1, json.readTree(Files.readString(stateRoot.resolve("memories.json"))).size());
        assertEquals("memories cleared", memory.execute(args("clear", null)));
        assertEquals("No saved memories", memory.execute(args("list", null)));
    }

    @Test
    void mutationsRequireApprovalAndClearFailsClosed() throws Exception {
        Tool memory = new MemoryTool(stateRoot);
        assertFalse(memory.requiresApproval(args("list", null)));
        assertTrue(memory.requiresApproval(args("save", "fact")));
        assertTrue(memory.requiresApproval(args("clear", null)));

        Files.createDirectory(stateRoot.resolve("memories.json"));
        Files.writeString(stateRoot.resolve("memories.json").resolve("must-survive.txt"), "safe");
        assertThrows(Exception.class, () -> memory.execute(args("clear", null)));
        assertTrue(Files.exists(stateRoot.resolve("memories.json").resolve("must-survive.txt")));
    }

    @Test
    void strictContractRejectsUnknownActionsAndFields() {
        Tool memory = new MemoryTool(stateRoot);
        assertThrows(IllegalArgumentException.class, () -> memory.execute(args("replace", "fact")));
        ObjectNode unknown = args("list", null).put("scope", "global");
        assertThrows(IllegalArgumentException.class, () -> memory.execute(unknown));
    }

    private ObjectNode args(String action, String fact) {
        ObjectNode result = json.createObjectNode().put("action", action);
        if (fact != null) result.put("fact", fact);
        return result;
    }
}
