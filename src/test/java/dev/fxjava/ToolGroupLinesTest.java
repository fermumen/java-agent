package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Composition and column-bounded truncation for live tool-group lines. */
class ToolGroupLinesTest {
    private final Ansi plain = Ansi.of(false);
    private final Ansi color = Ansi.of(true);

    @Test
    void runningLineIsDimBulletNameAndPreview() {
        assertEquals("● write_file write smoke.txt",
                ToolGroupLines.running("write_file", "write smoke.txt", 40, plain));
    }

    @Test
    void completedLineRewritesWithCheckOrCross() {
        assertEquals("\u001b[32m✓\u001b[0m \u001b[38;5;248mwrite_file\u001b[0m",
                ToolGroupLines.completed("write_file", "", false, 40, color));
        assertEquals("✗ echo",
                ToolGroupLines.completed("echo", "", true, 40, plain));
    }

    @Test
    void previewTruncatesWithinTheColumnBudget() {
        String longPreview = "abcdefghij".repeat(10);
        String line = ToolGroupLines.running("echo", longPreview, 30, plain);
        assertTrue(line.startsWith("● echo "));
        assertTrue(line.endsWith("…"));
        assertTrue(MarkdownConsole.visibleWidth(line) <= 30);
    }

    @Test
    void previewsFlattenToASingleLine() {
        assertEquals("● bash ls -la rm -rf /",
                ToolGroupLines.running("bash", "ls -la\nrm -rf /", 40, plain));
    }

    @Test
    void oversizedNamesTruncateBeforePreview() {
        String line = ToolGroupLines.running("verylongtoolname", "preview", 8, plain);
        assertEquals("● veryl…", line);
        assertTrue(MarkdownConsole.visibleWidth(line) < 9);
    }

    @Test
    void completedKeepsErrorPreviewDim() {
        String line = ToolGroupLines.completed("echo", "boom", true, 40, color);
        assertEquals("\u001b[31m✗\u001b[0m \u001b[38;5;248mecho\u001b[0m \u001b[38;5;243mboom\u001b[0m",
                line);
    }

    @Test
    void elapsedSuffixSurvivesPreviewTruncation() {
        String line = ToolGroupLines.completed("bash", "x".repeat(80), "1.2s", false, 30, plain);
        assertTrue(line.endsWith("… · 1.2s"), line);
        assertTrue(MarkdownConsole.visibleWidth(line) <= 30);
    }
}
