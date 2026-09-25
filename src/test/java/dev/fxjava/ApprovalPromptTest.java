package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decision mapping, grant-key normalization, and the boxed prompt bytes. */
class ApprovalPromptTest {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    @Test
    void singleLetterKeysMapToDecisions() {
        assertEquals(ApprovalPrompt.Decision.YES, ApprovalPrompt.decisionOf(KeyEvent.text("y")));
        assertEquals(ApprovalPrompt.Decision.YES, ApprovalPrompt.decisionOf(KeyEvent.text("Y")));
        assertEquals(ApprovalPrompt.Decision.NO, ApprovalPrompt.decisionOf(KeyEvent.text("n")));
        assertEquals(ApprovalPrompt.Decision.ALWAYS, ApprovalPrompt.decisionOf(KeyEvent.text("a")));
    }

    @Test
    void enterAndEscapeDenyAndOtherKeysAreIgnored() {
        assertEquals(ApprovalPrompt.Decision.NO, ApprovalPrompt.decisionOf(KeyEvent.enter()));
        assertEquals(ApprovalPrompt.Decision.NO,
                ApprovalPrompt.decisionOf(KeyEvent.of(KeyEvent.Kind.ESCAPE)));
        assertEquals(null, ApprovalPrompt.decisionOf(KeyEvent.of(KeyEvent.Kind.TAB)));
        assertEquals(null, ApprovalPrompt.decisionOf(KeyEvent.text("x")));
        assertEquals(null, ApprovalPrompt.decisionOf(KeyEvent.text("yes")));
        assertEquals(null, ApprovalPrompt.decisionOf(KeyEvent.ctrl('c')));
    }

    @Test
    void grantKeysUseCanonicalArgumentsAndPreserveToolAndValueCase() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String arguments = SessionRules.normalizeArguments(
                json.readTree("{\"command\":\"echo A\",\"working_directory\":\"src\"}"));
        String reordered = SessionRules.normalizeArguments(
                json.readTree("{ \"working_directory\" : \"src\", \"command\" : \"echo A\" }"));
        String key = ApprovalPrompt.grantKey("run_command", arguments);
        assertEquals(key, ApprovalPrompt.grantKey("run_command", reordered),
                "object key order and JSON formatting are insignificant");
        assertTrue(!key.equals(ApprovalPrompt.grantKey("Run_Command", arguments)),
                "tool name matching is case-sensitive");
        assertTrue(!key.equals(ApprovalPrompt.grantKey("run_command",
                SessionRules.normalizeArguments(json.readTree(
                        "{\"command\":\"echo a\",\"working_directory\":\"src\"}")))),
                "argument values preserve case");
        assertTrue(!key.equals(ApprovalPrompt.grantKey("run_command",
                SessionRules.normalizeArguments(json.readTree(
                        "{\"command\":\"echo A\",\"working_directory\":\".\"}")))),
                "all structured arguments participate in the identity");
    }

    @Test
    void renderDrawsBorderedBoxWithBoldToolAndOptionsLegend() {
        ApprovalPrompt.Box box = ApprovalPrompt.render(out, Ansi.of(true), 60, "write_file",
                "create smoke.txt with a greeting");
        String rendered = output();
        assertEquals(3, box.rows);
        assertTrue(rendered.startsWith("\n"));
        assertTrue(rendered.contains("┌─ Allow \u001b[1mwrite_file\u001b[0m?"));
        assertTrue(rendered.contains("│ create smoke.txt with a greeting"));
        assertTrue(rendered.contains("└─ y yes · n no · a always this session "));
        for (String line : box.lines()) {
            assertEquals(60, MarkdownConsole.visibleWidth(stripSgr(line)),
                    "row must span the full width: " + line);
        }
    }

    private static String stripSgr(String line) {
        return line.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    @Test
    void previewWrapsInsideTheBorder() {
        ApprovalPrompt.render(out, Ansi.of(false), 30, "run", "alpha beta gamma delta epsilon");
        String[] lines = output().split("\n");
        assertTrue(lines.length >= 5, "wrapped body rows expected: " + output());
        assertTrue(lines[2].startsWith("│ alpha beta "));
        assertTrue(lines[3].startsWith("│ "));
    }

    @Test
    void emptyPreviewCollapsesToBordersOnly() {
        ApprovalPrompt.Box box = ApprovalPrompt.render(out, Ansi.of(false), 50, "echo", "");
        assertEquals(2, box.rows);
    }

    @Test
    void eraseRestoresThePreBoxCursorRow() {
        ApprovalPrompt.Box box = ApprovalPrompt.render(out, Ansi.of(false), 40, "tool", "preview");
        reset();
        ApprovalPrompt.erase(out, Ansi.of(false), box);
        assertEquals("\u001b[3A\r\u001b[2K\n\r\u001b[2K\n\r\u001b[2K\r\u001b[2K",
                output());
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void reset() {
        bytes.reset();
    }
}
