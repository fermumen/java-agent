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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(write.execute(writeArgs).startsWith("wrote"));
        assertTrue(read.execute(json.createObjectNode().put("path", "nested/example.txt"))
                .contains("2\ttwo"));
        edit.execute(json.createObjectNode().put("path", "nested/example.txt")
                .put("old_string", "two").put("new_string", "second"));
        assertEquals("one\nsecond\n", Files.readString(temporaryDirectory.resolve("nested/example.txt")));
    }

    @Test
    void externalReadIsCanonicalAndRequiresPerCallApproval() throws Exception {
        Path external = Files.createTempFile("java-agent-external-", ".txt");
        Files.writeString(external, "external\n");
        Files.writeString(temporaryDirectory.resolve("inside.txt"), "inside\n");
        Tool read = named(WorkspaceTools.create(temporaryDirectory), "read_file");

        assertFalse(read.requiresApproval());
        assertFalse(read.requiresApproval(json.createObjectNode().put("path", "inside.txt")));
        assertTrue(read.requiresApproval(json.createObjectNode().put("path", external.toString())));
        assertTrue(read.execute(json.createObjectNode().put("path", external.toString()))
                .startsWith("<path>" + external.toRealPath()));
    }

    @Test
    void symlinkEscapeIsClassifiedAsExternalWhenSupported() throws Exception {
        Path outside = Files.createTempDirectory("java-agent-outside-");
        Files.writeString(outside.resolve("outside.txt"), "outside\n");
        Path link = temporaryDirectory.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException error) {
            return;
        }
        Tool read = named(WorkspaceTools.create(temporaryDirectory), "read_file");
        ObjectNode arguments = json.createObjectNode().put("path", "outside-link/outside.txt");
        assertTrue(read.requiresApproval(arguments));
        assertTrue(read.execute(arguments).contains("outside"));
    }

    @Test
    void terminalCompatibilityKeepsExecWhileAdvertisingDurableActions() throws Exception {
        Tool terminal = named(WorkspaceTools.create(temporaryDirectory), "terminal");
        assertEquals("exec", terminal.parameters().path("properties").path("action").path("enum").path(0).asText());
        assertEquals(12, terminal.parameters().path("properties").path("action").path("enum").size());
        String result = terminal.execute(json.createObjectNode().put("action", "exec")
                .put("command", "echo terminal-contract"));
        assertTrue(result.contains("terminal-contract"));
        assertTrue(result.contains("Exit code: 0"));
    }

    private static Tool named(List<Tool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }
}
