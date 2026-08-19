package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows is the authoritative filesystem target for the Java port. */
@EnabledOnOs(OS.WINDOWS)
class WorkspaceToolsWindowsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void backslashPathsWorkAcrossReadWriteCopyRenameAndDelete() throws Exception {
        List<Tool> tools = WorkspaceTools.create(workspace);
        named(tools, "write_file").execute(json.createObjectNode()
                .put("path", "nested\\source.txt").put("content", "windows\r\n"));
        assertTrue(named(tools, "read_file").execute(json.createObjectNode()
                .put("path", "nested\\source.txt")).contains("windows"));
        named(tools, "copy_file").execute(json.createObjectNode()
                .put("source", "nested\\source.txt").put("destination", "copied\\copy.txt"));
        named(tools, "rename_file").execute(json.createObjectNode()
                .put("old_path", "copied\\copy.txt").put("new_path", "moved\\final.txt"));
        assertEquals("windows\r\n", Files.readString(workspace.resolve("moved\\final.txt")));
        named(tools, "delete_file").execute(json.createObjectNode().put("path", "moved\\final.txt"));
        assertTrue(Files.notExists(workspace.resolve("moved\\final.txt")));
    }

    @Test
    void absoluteExternalWindowsPathsAreCanonicalAndApprovalGated() throws Exception {
        List<Tool> tools = WorkspaceTools.create(workspace);
        Tool read = named(tools, "read_file");
        Path inside = workspace.resolve("absolute.txt");
        Files.writeString(inside, "inside\n");
        assertFalse(read.requiresApproval(json.createObjectNode().put("path", inside.toString())));

        Path external = Files.createTempFile("java-agent-win-external-", ".txt");
        Files.writeString(external, "outside\n");
        assertTrue(read.requiresApproval(json.createObjectNode().put("path", external.toString())));
        assertTrue(read.execute(json.createObjectNode().put("path", external.toString())).contains("outside"));
    }

    @Test
    void pathComparisonUsesWindowsCaseInsensitivity() throws Exception {
        Files.writeString(workspace.resolve("CaseName.txt"), "case\n");
        WorkspaceTools.Workspace paths = new WorkspaceTools.Workspace(workspace);
        assertTrue(Files.isSameFile(workspace.resolve("CaseName.txt"),
                paths.resolveExisting("casename.TXT")));
    }

    @Test
    void tildeExpandsToWindowsUserHomeAndRequiresApproval() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Path target = Files.createTempFile(home, "java-agent-home-", ".txt");
        try {
            String input = "~\\" + target.getFileName();
            Tool read = named(WorkspaceTools.create(workspace), "read_file");
            assertTrue(read.requiresApproval(json.createObjectNode().put("path", input)));
            assertTrue(read.execute(json.createObjectNode().put("path", input)).contains(target.getFileName().toString()));
        } finally {
            Files.deleteIfExists(target);
        }
    }

    private static Tool named(List<Tool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }
}
