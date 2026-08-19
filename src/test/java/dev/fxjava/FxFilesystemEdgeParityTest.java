package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Additional compact cases ported from per-tool fx unit tests. */
class FxFilesystemEdgeParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    private List<Tool> tools;

    @BeforeEach
    void createTools() throws IOException {
        tools = WorkspaceTools.create(workspace);
    }

    @Test
    void fileInfoPortsEpochAndActiveExtensionBehavior() throws Exception {
        Files.createDirectories(workspace.resolve("src.dir"));
        Path plain = Files.writeString(workspace.resolve("plain"), "abc");
        Path parentDot = Files.writeString(workspace.resolve("src.dir/index"), "x");
        Path hidden = Files.writeString(workspace.resolve(".bashrc"), "x");
        Path trailing = Files.writeString(workspace.resolve("foo."), "x");
        Files.setLastModifiedTime(plain, FileTime.from(Instant.EPOCH));

        String plainInfo = named("file_info").execute(args("path", "plain"));
        assertTrue(plainInfo.contains("modified: 1970-01-01T00:00:00Z\n"));
        assertFalse(plainInfo.contains("extension:"));
        assertTrue(named("file_info").execute(args("path", path("src.dir/index")))
                .contains("extension: " + path("dir/index") + "\n"));
        assertTrue(named("file_info").execute(args("path", ".bashrc"))
                .contains("extension: bashrc\n"));
        assertTrue(named("file_info").execute(args("path", "foo."))
                .contains("extension: \n"));
        assertTrue(Files.exists(parentDot) && Files.exists(hidden) && Files.exists(trailing));
    }

    @Test
    void semanticSearchPortsStopwordsFilenameWeightAndOversizedSkip() throws Exception {
        Files.writeString(workspace.resolve("RecoveryAgent.java"), "plain content\n");
        Files.writeString(workspace.resolve("Other.java"), "recovery agent\n");
        Files.write(workspace.resolve("HugeRecovery.java"), new byte[100 * 1024 + 1]);

        assertEquals("[search] empty query\n",
                named("semantic_search").execute(args("query", "the and how")));
        String result = named("semantic_search").execute(args("query", "recovery agent"));
        assertTrue(result.indexOf("RecoveryAgent.java") < result.indexOf("Other.java"));
        assertFalse(result.contains("HugeRecovery.java"));
    }

    @Test
    void globPortsRegularFileRootAndHundredResultCap() throws Exception {
        Files.writeString(workspace.resolve("single.java"), "class Single {}\n");
        assertEquals("[glob] 1 matches for *.java\n - single.java\n",
                named("glob_files").execute(args("pattern", "*.java", "path", "single.java")));
        assertEquals("[glob] no matches for *.txt\n",
                named("glob_files").execute(args("pattern", "*.txt", "path", "single.java")));

        Files.createDirectories(workspace.resolve("many"));
        for (int index = 0; index < 101; index++) {
            Files.writeString(workspace.resolve("many/f" + index + ".txt"), "x\n");
        }
        String capped = named("glob_files").execute(args("pattern", "*.txt", "path", "many"));
        assertTrue(capped.startsWith("[glob] 100 matches for *.txt\n"));
        assertTrue(capped.endsWith("... truncated to first 100 matches\n"));
        assertEquals("[glob] count 101 matches for *.txt\n",
                named("glob_files").execute(args("pattern", "*.txt", "path", "many", "mode", "count")));
    }

    @Test
    void listPortsHundredEntryCapAfterIgnoredFiltering() throws Exception {
        Files.createDirectory(workspace.resolve(".git"));
        for (int index = 0; index < 101; index++) Files.writeString(workspace.resolve("f" + index), "x");
        String result = named("list_files").execute(args());
        assertEquals(100, result.lines().filter(line -> line.startsWith("- ")).count());
        assertTrue(result.endsWith("... and more entries (showing first 100)\n"));
        assertFalse(result.contains(".git"));
    }

    @Test
    void copyAndRenamePortNonRegularDestinationAndMissingSourceFailures() throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "source\n");
        Files.createDirectory(workspace.resolve("destination"));
        assertThrows(IOException.class, () -> named("copy_file").execute(args(
                "source", "source.txt", "destination", "destination")));
        assertTrue(Files.isDirectory(workspace.resolve("destination")));
        assertThrows(IOException.class, () -> named("copy_file").execute(args(
                "source", "missing.txt", "destination", "copy.txt")));
        assertThrows(IOException.class, () -> named("rename_file").execute(args(
                "old_path", "missing.txt", "new_path", "moved.txt")));
    }

    @Test
    void externalMutationsUseAbsoluteCanonicalDisplay() throws Exception {
        Path externalRoot = Files.createTempDirectory("java-agent-mutation-external-");
        Path source = Files.writeString(externalRoot.resolve("source.txt"), "external\n");
        Tool copy = named("copy_file");
        ObjectNode copyArgs = args("source", source.toString(), "destination", "copied.txt");
        assertTrue(copy.requiresApproval(copyArgs));
        assertTrue(copy.execute(copyArgs).startsWith("copied " + source.toRealPath()));
        assertEquals("external\n", Files.readString(workspace.resolve("copied.txt")));
    }

    private Tool named(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private ObjectNode args(Object... fields) {
        ObjectNode result = json.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            String field = (String) fields[index];
            Object value = fields[index + 1];
            if (value instanceof Integer number) result.put(field, number);
            else result.put(field, (String) value);
        }
        return result;
    }

    private static String path(String value) {
        return value.replace('/', java.io.File.separatorChar);
    }
}
