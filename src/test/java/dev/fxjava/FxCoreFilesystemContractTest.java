package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
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

/** Output and edge-case contracts ported from fx's core filesystem tool tests. */
class FxCoreFilesystemContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    private List<Tool> tools;

    @BeforeEach
    void createTools() throws IOException {
        tools = WorkspaceTools.create(workspace);
    }

    @Test
    void listFilesPortsEmptySuffixOrderingAndRegularFileFailure() throws Exception {
        Tool list = named("list_files");
        assertEquals(".:\n(empty)\n", list.execute(args()));

        Files.writeString(workspace.resolve("z.txt"), "z\n");
        Files.createDirectory(workspace.resolve("a-dir"));
        assertEquals(".:\n- a-dir/\n- z.txt\n", list.execute(args()));
        assertThrows(IOException.class, () -> list.execute(args("path", "z.txt")));
    }

    @Test
    void readFilePortsTagsRangesWhitespaceAndBeyondEnd() throws Exception {
        Files.writeString(workspace.resolve("lines.txt"), "one\ntwo\nthree\n");
        Tool read = named("read_file");

        assertEquals("<path>lines.txt</path>\n<content>\n1\tone\n2\ttwo\n3\tthree\n</content>",
                read.execute(args("path", "  lines.txt\t")));
        assertEquals("<path>lines.txt</path>\n<content>\n2\ttwo\n3\tthree\n"
                        + "... [showing 2 of 3 lines; use start_line/line_count to read more.]\n</content>",
                read.execute(args("path", "lines.txt", "start_line", 2, "line_count", 2)));
        assertEquals("<path>lines.txt</path>\n<content>\n"
                        + "... [start_line 5 is beyond end of file; total lines 3]\n</content>",
                read.execute(args("path", "lines.txt", "start_line", 5)));
        assertThrows(IllegalArgumentException.class, () -> read.execute(args("path", " \n ")));
    }

    @Test
    void readFilePortsEmptyFileAndByteLimit() throws Exception {
        Files.writeString(workspace.resolve("empty.txt"), "");
        assertEquals("<path>empty.txt</path>\n<content>\n</content>",
                named("read_file").execute(args("path", "empty.txt")));

        Files.write(workspace.resolve("large.txt"), new byte[50 * 1024 + 1]);
        assertThrows(IOException.class,
                () -> named("read_file").execute(args("path", "large.txt")));
    }

    @Test
    void grepFilesPortsCaseIncludePaginationContextAndModes() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/a.java"), "before\nNeedle here\nafter\nneedle again\n");
        Files.writeString(workspace.resolve("src/b.txt"), "needle hidden by include\n");
        Tool grep = named("grep_files");

        assertEquals("[grep] 2 matches for needle\n"
                        + " - " + path("src/a.java") + ":2: Needle here\n"
                        + " - " + path("src/a.java") + ":4: needle again\n",
                grep.execute(args("pattern", "needle", "path", "src", "include", "*.java",
                        "case_insensitive", true)));
        assertEquals("[grep] 1 matches for needle (showing 2-2 of 2)\n"
                        + "   " + path("src/a.java") + ":3- after\n"
                        + " - " + path("src/a.java") + ":4: needle again\n"
                        + "   " + path("src/a.java") + ":5- \n",
                grep.execute(args("pattern", "needle", "path", "src", "include", "*.java",
                        "case_insensitive", true, "head_limit", 1, "offset", 1, "context_lines", 1)));
        assertEquals("[grep] 1 files with matches for needle\n - " + path("src/a.java") + "\n",
                grep.execute(args("pattern", "needle", "path", "src", "include", "*.java",
                        "case_insensitive", true, "mode", "files_with_matches")));
        assertEquals("[grep] count 2 matching lines in 1 files for needle\n",
                grep.execute(args("pattern", "needle", "path", "src", "include", "*.java",
                        "case_insensitive", true, "mode", "count")));
    }

    @Test
    void grepFilesPortsNoMatchesDirectFileAndLiteralSearch() throws Exception {
        Files.writeString(workspace.resolve("literal.txt"), "a.*b\nordinary\n");
        Tool grep = named("grep_files");
        assertEquals("[grep] 1 matches for .*\n - literal.txt:1: a.*b\n",
                grep.execute(args("pattern", ".*", "path", "literal.txt")));
        assertEquals("[grep] no matches for missing\n",
                grep.execute(args("pattern", "missing", "path", "literal.txt")));
    }

    @Test
    void writeAndEditPortTypedInputsUniqueReplacementAndLimits() throws Exception {
        Tool write = named("write_file");
        Tool edit = named("edit_file");
        assertEquals("wrote " + path("nested/file.txt"),
                write.execute(args("path", path("nested/file.txt"), "content", "old value\n")));
        assertEquals("edited " + path("nested/file.txt"), edit.execute(args(
                "path", path("nested/file.txt"), "old_string", "old", "new_string", "new")));
        assertEquals("new value\n", Files.readString(workspace.resolve("nested/file.txt")));

        Files.writeString(workspace.resolve("duplicates.txt"), "same same\n");
        assertThrows(IOException.class, () -> edit.execute(args(
                "path", "duplicates.txt", "old_string", "same", "new_string", "next")));
        assertThrows(IllegalArgumentException.class, () -> edit.execute(args(
                "path", "duplicates.txt", "old_string", "", "new_string", "next")));
        assertThrows(IOException.class, () -> write.execute(args(
                "path", "huge.txt", "content", "x".repeat(4 * 1024 * 1024 + 1))));
        assertFalse(Files.exists(workspace.resolve("huge.txt")));
    }

    @Test
    void schemasUseFxArgumentNames() {
        assertTrue(named("grep_files").parameters().path("required").toString().contains("pattern"));
        assertFalse(named("grep_files").parameters().path("properties").has("query"));
        assertTrue(named("edit_file").parameters().path("required").toString().contains("old_string"));
        assertTrue(named("edit_file").parameters().path("required").toString().contains("new_string"));
        assertFalse(named("edit_file").parameters().path("properties").has("old_text"));
    }

    private Tool named(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private ObjectNode args(Object... fields) {
        ObjectNode result = json.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            String name = (String) fields[index];
            Object value = fields[index + 1];
            if (value instanceof Integer number) result.put(name, number);
            else if (value instanceof Boolean bool) result.put(name, bool);
            else result.put(name, (String) value);
        }
        return result;
    }

    private static String path(String value) {
        return value.replace('/', java.io.File.separatorChar);
    }
}
