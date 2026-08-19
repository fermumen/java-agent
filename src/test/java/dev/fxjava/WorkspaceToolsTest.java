package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void writeReadAndEditStayInsideWorkspace() throws Exception {
        List<Tool> tools = WorkspaceTools.create(temporaryDirectory);
        Tool write = named(tools, "write_file");
        Tool read = named(tools, "read_file");
        Tool edit = named(tools, "edit_file");

        ObjectNode writeArgs = json.createObjectNode().put("path", "nested/example.txt").put("content", "one\ntwo\n");
        assertTrue(write.execute(writeArgs).startsWith("Wrote"));
        assertTrue(read.execute(json.createObjectNode().put("path", "nested/example.txt"))
                .contains("2 | two"));
        edit.execute(json.createObjectNode().put("path", "nested/example.txt")
                .put("old_text", "two").put("new_text", "second"));
        assertEquals("one\nsecond\n", Files.readString(temporaryDirectory.resolve("nested/example.txt")));
    }

    @Test
    void rejectsLexicalTraversal() throws Exception {
        WorkspaceTools.Workspace workspace = new WorkspaceTools.Workspace(temporaryDirectory);
        assertThrows(IOException.class, () -> workspace.resolveForWrite("../outside.txt"));
    }

    @Test
    void rejectsSymlinkTraversalWhenSupported() throws Exception {
        Path outside = Files.createTempDirectory("java-agent-outside-");
        Path link = temporaryDirectory.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException error) {
            return;
        }
        WorkspaceTools.Workspace workspace = new WorkspaceTools.Workspace(temporaryDirectory);
        assertThrows(IOException.class, () -> workspace.resolveForWrite("outside-link/file.txt"));
    }

    private static Tool named(List<Tool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }
}
