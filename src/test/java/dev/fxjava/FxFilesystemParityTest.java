package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compact behavior matrix ported from fx/src/tools/filesystem/*.zig. */
class FxFilesystemParityTest {
    private static final Set<String> FX_FILESYSTEM_TOOLS = Set.of(
            "list_files", "glob_files", "grep_files", "read_file", "write_file", "edit_file",
            "delete_file", "rename_file", "copy_file", "create_folder", "file_info",
            "semantic_search", "open_file");
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "write_file", "edit_file", "delete_file", "rename_file", "copy_file",
            "create_folder", "open_file");

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    private Map<String, Tool> tools;

    @BeforeEach
    void createTools() throws IOException {
        tools = new LinkedHashMap<>();
        for (Tool tool : WorkspaceTools.create(workspace)) tools.put(tool.name(), tool);
    }

    @Test
    void advertisesEveryFxFilesystemToolWithClosedObjectSchemas() {
        assertTrue(tools.keySet().containsAll(FX_FILESYSTEM_TOOLS));
        for (String name : FX_FILESYSTEM_TOOLS) {
            ObjectNode schema = tools.get(name).parameters();
            assertEquals("object", schema.path("type").asText(), name);
            assertTrue(schema.path("properties").isObject(), name);
            assertFalse(schema.path("additionalProperties").asBoolean(true), name);
        }
    }

    @Test
    void fxMutationClassificationRequiresApproval() {
        for (String name : FX_FILESYSTEM_TOOLS) {
            assertEquals(MUTATING_TOOLS.contains(name), tools.get(name).requiresApproval(), name);
        }
    }

    @TestFactory
    Stream<DynamicTest> requiredStringArgumentsRejectMissingOrWrongTypes() {
        Map<String, List<String>> required = Map.of(
                "delete_file", List.of("path"),
                "rename_file", List.of("old_path", "new_path"),
                "copy_file", List.of("source", "destination"),
                "create_folder", List.of("path"),
                "file_info", List.of("path"),
                "semantic_search", List.of("query"));
        return required.entrySet().stream().flatMap(entry -> entry.getValue().stream().flatMap(field -> Stream.of(
                DynamicTest.dynamicTest(entry.getKey() + " requires " + field, () ->
                        assertThrows(IllegalArgumentException.class,
                                () -> tools.get(entry.getKey()).execute(json.createObjectNode()))),
                DynamicTest.dynamicTest(entry.getKey() + " types " + field, () -> {
                    ObjectNode arguments = validArguments(entry.getKey());
                    arguments.put(field, 7);
                    assertThrows(IllegalArgumentException.class,
                            () -> tools.get(entry.getKey()).execute(arguments));
                }))));
    }

    @Test
    void createFolderPortsNestedExistingAndFileCollisionCases() throws Exception {
        Tool create = tools.get("create_folder");
        assertEquals(pathMessage("created nested/dir"),
                create.execute(args("path", path("nested/dir"))));
        assertTrue(Files.isDirectory(workspace.resolve("nested/dir")));
        assertEquals(pathMessage("directory already exists: nested/dir"),
                create.execute(args("path", path("nested/dir"))));

        Files.writeString(workspace.resolve("occupied"), "file\n");
        IOException error = assertThrows(IOException.class,
                () -> create.execute(args("path", "occupied")));
        assertTrue(error.getMessage().contains("target exists and is not a directory"));
    }

    @Test
    void copyFilePortsParentsReplacementAndSamePathCases() throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "source\n");
        Files.writeString(workspace.resolve("old.txt"), "old\n");
        Tool copy = tools.get("copy_file");

        assertEquals(pathMessage("copied source.txt -> nested/dest.txt"), copy.execute(args(
                "source", "source.txt", "destination", path("nested/dest.txt"))));
        assertEquals("source\n", Files.readString(workspace.resolve("nested/dest.txt")));
        assertEquals(pathMessage("copied source.txt -> old.txt"), copy.execute(args(
                "source", "source.txt", "destination", "old.txt")));
        assertEquals("source\n", Files.readString(workspace.resolve("old.txt")));
        assertEquals("copied source.txt -> source.txt", copy.execute(args(
                "source", "source.txt", "destination", "source.txt")));
    }

    @Test
    void renameFilePortsParentsReplacementAndSamePathCases() throws Exception {
        Files.writeString(workspace.resolve("old.txt"), "hello\n");
        Tool rename = tools.get("rename_file");

        assertEquals(pathMessage("renamed old.txt -> nested/new.txt"), rename.execute(args(
                "old_path", "old.txt", "new_path", path("nested/new.txt"))));
        assertFalse(Files.exists(workspace.resolve("old.txt")));
        assertEquals("hello\n", Files.readString(workspace.resolve("nested/new.txt")));
        assertEquals(pathMessage("renamed nested/new.txt -> nested/new.txt"), rename.execute(args(
                "old_path", path("nested/new.txt"), "new_path", path("nested/new.txt"))));

        Files.writeString(workspace.resolve("replacement.txt"), "replace me\n");
        rename.execute(args("old_path", path("nested/new.txt"), "new_path", "replacement.txt"));
        assertEquals("hello\n", Files.readString(workspace.resolve("replacement.txt")));
    }

    @Test
    void deleteFilePortsRegularEmptyDirectoryNonEmptyAndMissingCases() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "notes\n");
        Files.createDirectories(workspace.resolve("empty"));
        Files.createDirectories(workspace.resolve("non-empty"));
        Files.writeString(workspace.resolve("non-empty/child.txt"), "child\n");
        Tool delete = tools.get("delete_file");

        assertEquals("deleted notes.txt", delete.execute(args("path", "notes.txt")));
        assertEquals("deleted empty", delete.execute(args("path", "empty")));
        IOException notEmpty = assertThrows(IOException.class,
                () -> delete.execute(args("path", "non-empty")));
        assertTrue(notEmpty.getMessage().contains("directory not empty"));
        assertThrows(IOException.class, () -> delete.execute(args("path", "missing.txt")));
    }

    @Test
    void fileInfoPortsFileDirectoryExtensionAndTimestampShape() throws Exception {
        Files.writeString(workspace.resolve("file.txt"), "hello\n");
        Files.createDirectories(workspace.resolve("dir.ext"));
        Tool info = tools.get("file_info");

        String file = info.execute(args("path", "  file.txt  "));
        assertTrue(file.startsWith("path: file.txt\ntype: file\nsize: 6 bytes\nmodified: "));
        assertTrue(file.endsWith("extension: txt\n"));
        String directory = info.execute(args("path", "dir.ext"));
        assertTrue(directory.contains("type: directory\n"));
        assertFalse(directory.contains("extension:"));
        assertThrows(IllegalArgumentException.class, () -> info.execute(args("path", " \t ")));
    }

    @Test
    void globFilesPortsMatchesCountNarrowingAndZeroCases() throws Exception {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("src/main/App.txt"), "text\n");
        Files.writeString(workspace.resolve("root.java"), "class Root {}\n");
        Tool glob = tools.get("glob_files");

        String matches = glob.execute(args("pattern", "*.java", "path", path("src/main")));
        assertEquals("[glob] 1 matches for *.java\n - " + path("src/main/App.java") + "\n", matches);
        assertEquals("[glob] count 1 matches for *.java\n",
                glob.execute(args("pattern", "*.java", "path", path("src/main"), "mode", "count")));
        assertEquals("[glob] no matches for *.zig\n",
                glob.execute(args("pattern", "*.zig", "path", path("src/main"))));
    }

    @Test
    void semanticSearchPortsRankingNoResultAndDirectFileCases() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/AgentRuntime.java"),
                "final class AgentRuntime { // session recovery\n}\n");
        Files.writeString(workspace.resolve("src/Other.java"), "final class Other {}\n");
        Tool search = tools.get("semantic_search");

        String result = search.execute(args("query", "agent recovery", "path", "src"));
        assertTrue(result.startsWith("[search] 1 results for: agent recovery\n"));
        assertTrue(result.contains(path("src/AgentRuntime.java") + ":1:"));
        assertEquals("[search] no results for: unavailable\n",
                search.execute(args("query", "unavailable", "path", "src")));
        assertTrue(search.execute(args("query", "recovery", "path", path("src/AgentRuntime.java")))
                .contains(path("src/AgentRuntime.java") + ":1:"));
    }

    @Test
    void externalFilesystemPathsAreCanonicalAndApprovalGated() throws Exception {
        Path external = workspace.resolveSibling("outside.txt");
        Files.writeString(external, "outside\n");
        WorkspaceTools.Workspace paths = new WorkspaceTools.Workspace(workspace);
        assertEquals(external.toRealPath(), paths.resolveExisting("../outside.txt"));
        Tool read = tools.get("read_file");
        assertTrue(read.requiresApproval(args("path", "../outside.txt")));
        assertTrue(read.execute(args("path", "../outside.txt")).contains("outside"));
    }

    private ObjectNode validArguments(String tool) {
        switch (tool) {
            case "delete_file":
            case "file_info":
                return args("path", "missing");
            case "rename_file":
                return args("old_path", "missing", "new_path", "new");
            case "copy_file":
                return args("source", "missing", "destination", "new");
            case "create_folder":
                return args("path", "new");
            case "semantic_search":
                return args("query", "needle");
            default:
                throw new IllegalArgumentException(tool);
        }
    }

    private ObjectNode args(Object... fields) {
        ObjectNode result = json.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            Object value = fields[index + 1];
            if (value instanceof Integer) result.put((String) fields[index], (Integer) value);
            else result.put((String) fields[index], (String) value);
        }
        return result;
    }

    private static String path(String slashPath) {
        return slashPath.replace('/', java.io.File.separatorChar);
    }

    private static String pathMessage(String value) {
        return value.replace('/', java.io.File.separatorChar);
    }
}
