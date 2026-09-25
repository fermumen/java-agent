package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical search boundaries and bounded streaming read/search behavior. */
class FilesystemReadSafetyTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    private Path workspace;
    private List<Tool> tools;

    @BeforeEach
    void createWorkspace() throws Exception {
        workspace = Files.createDirectory(temporary.resolve("workspace"));
        tools = WorkspaceTools.create(workspace);
    }

    @Test
    void grepStreamsLargeTextAndReportsLinesWhoseSuffixWasNotRetained() throws Exception {
        Files.writeString(workspace.resolve("large.txt"), "intro λ\nneedle\n".repeat(10_000));
        String result = named("grep_files").execute(args("pattern", "needle", "mode", "count"));
        assertTrue(result.startsWith("[grep] count 10000 matching lines in 1 files for needle"), result);

        Files.writeString(workspace.resolve("wide.csv"), "x".repeat(20_000) + "needle-at-suffix\n");
        String wide = named("grep_files").execute(args("pattern", "needle-at-suffix"));
        assertTrue(wide.contains("no matches for needle-at-suffix"), wide);
        assertTrue(wide.contains("omitted line suffixes may contain matches"), wide);
    }

    @Test
    void workspaceSearchesSkipEscapingFileLinksButKeepInternalLinksAndApprovedExternalRoots() throws Exception {
        Path external = Files.writeString(temporary.resolve("outside.txt"), "outsidemark outsideword\n");
        Path internal = Files.writeString(workspace.resolve("inside.txt"), "internalmark inworkspace\n");
        try {
            Files.createSymbolicLink(workspace.resolve("linked-outside.txt"), external);
            Files.createSymbolicLink(workspace.resolve("linked-inside.txt"), internal);
        } catch (UnsupportedOperationException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable: " + unavailable);
            return;
        }

        String grep = named("grep_files").execute(args("pattern", "outsidemark", "path", "."));
        assertFalse(grep.contains("outsidemark outsideword"), grep);
        assertTrue(grep.contains("outside the selected search root skipped"), grep);

        String semantic = named("semantic_search").execute(args("query", "outsideword", "path", "."));
        assertFalse(semantic.contains("outside.txt"), semantic);
        assertTrue(semantic.contains("outside the selected search root skipped"), semantic);

        String internalGrep = named("grep_files").execute(args("pattern", "internalmark", "path", "."));
        assertTrue(internalGrep.contains("inside.txt:1: internalmark"), internalGrep);
        assertTrue(internalGrep.startsWith("[grep] 1 matches"),
                "the canonical target is searched once even when an in-root alias exists: " + internalGrep);

        Path externalRoot = Files.createDirectory(temporary.resolve("external-root"));
        Files.writeString(externalRoot.resolve("approved.txt"), "approvedoutsideword\n");
        Tool grepTool = named("grep_files");
        ObjectNode externalArgs = args("pattern", "approvedoutsideword", "path", externalRoot.toString());
        assertTrue(grepTool.requiresApproval(externalArgs));
        assertTrue(grepTool.execute(externalArgs).contains(externalRoot.resolve("approved.txt").toRealPath().toString()));

        Tool semanticTool = named("semantic_search");
        ObjectNode semanticArgs = args("query", "approvedoutsideword", "path", externalRoot.toString());
        assertTrue(semanticTool.requiresApproval(semanticArgs));
        assertTrue(semanticTool.execute(semanticArgs).contains("approved.txt"));
    }

    @Test
    void searchByteCutInsideUtf8CodePointIsExplicitAndNeverReturnedAsReplacementText() throws Exception {
        Path text = temporary.resolve("multibyte.txt");
        Files.writeString(text, "AλB\n", StandardCharsets.UTF_8);
        try (BoundedTextReader reader = BoundedTextReader.open(text, 2, 32)) {
            BoundedTextReader.Line line = reader.readLine();
            assertTrue(line.partial());
            assertTrue(reader.byteLimitReached());
            assertTrue(line.text().equals("A") || line.text().isEmpty(), line.text());
            assertFalse(line.text().contains("\ufffd"), "an incomplete UTF-8 code point is never presented as file text");
        }
    }

    private Tool named(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private ObjectNode args(Object... fields) {
        ObjectNode result = json.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            String name = (String) fields[index];
            Object value = fields[index + 1];
            if (value instanceof Integer) result.put(name, (Integer) value);
            else result.put(name, String.valueOf(value));
        }
        return result;
    }
}
