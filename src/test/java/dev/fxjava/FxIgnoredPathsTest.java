package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of fx default ignored-entry tests for list and recursive discovery. */
class FxIgnoredPathsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void listFiltersDefaultIgnoredNamesBeforeRendering() throws Exception {
        Files.createDirectories(workspace.resolve(".git"));
        Files.createDirectories(workspace.resolve("node_modules"));
        Files.writeString(workspace.resolve("visible.txt"), "visible\n");
        String result = named("list_files").execute(json.createObjectNode());
        assertTrue(result.contains("- visible.txt\n"));
        assertFalse(result.contains(".git"));
        assertFalse(result.contains("node_modules"));
    }

    @Test
    void recursiveSearchSkipsIgnoredRootsButExplicitRootRemainsSearchable() throws Exception {
        Files.createDirectories(workspace.resolve("node_modules/pkg"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("node_modules/pkg/ignored.txt"), "needle\n");
        Files.writeString(workspace.resolve("src/visible.txt"), "needle\n");

        String broad = named("grep_files").execute(json.createObjectNode().put("pattern", "needle"));
        assertTrue(broad.contains(path("src/visible.txt")));
        assertFalse(broad.contains("ignored.txt"));

        String explicit = named("grep_files").execute(json.createObjectNode()
                .put("pattern", "needle").put("path", "node_modules"));
        assertTrue(explicit.contains(path("node_modules/pkg/ignored.txt")));
    }

    @Test
    void globAndSemanticSearchShareIgnoredPolicy() throws Exception {
        Files.createDirectories(workspace.resolve("build"));
        Files.createDirectories(workspace.resolve("source"));
        Files.writeString(workspace.resolve("build/Hidden.java"), "class Hidden { }\n");
        Files.writeString(workspace.resolve("source/Visible.java"), "class Visible { }\n");

        assertFalse(named("glob_files").execute(json.createObjectNode().put("pattern", "**/*.java"))
                .contains("Hidden.java"));
        assertFalse(named("semantic_search").execute(json.createObjectNode().put("query", "Hidden"))
                .contains("Hidden.java"));
    }

    private Tool named(String name) throws Exception {
        List<Tool> tools = WorkspaceTools.create(workspace);
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static String path(String value) {
        return value.replace('/', java.io.File.separatorChar);
    }
}
