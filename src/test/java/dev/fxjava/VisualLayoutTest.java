package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wrap math parity checks against fx's visual_layout.zig behavior. */
class VisualLayoutTest {
    private static void assertCursor(String text, int offset, int columns,
                                     int expectedRow, int expectedColumn) {
        VisualLayout.Position position = VisualLayout.of(text, columns).cursorAt(offset);
        assertEquals(expectedRow, position.row(), "row for offset " + offset);
        assertEquals(expectedColumn, position.column(), "column for offset " + offset);
    }

    @Test
    void emptyTextIsOneRowWithCursorAtOrigin() {
        VisualLayout layout = VisualLayout.of("", 80);
        assertEquals(1, layout.totalRows());
        assertCursor("", 0, 80, 0, 0);
        assertEquals(VisualLayout.PROMPT_CELLS + 1, layout.terminalColumn(layout.cursorAt(0)));
    }

    @Test
    void shortLineFitsOneRow() {
        VisualLayout layout = VisualLayout.of("hello", 80);
        assertEquals(1, layout.totalRows());
        assertEquals(List.of("hello"), rows(layout));
        assertCursor("hello", 3, 80, 0, 3);
        assertCursor("hello", 5, 80, 0, 5);
    }

    @Test
    void hardNewlinesSplitRows() {
        VisualLayout layout = VisualLayout.of("ab\ncd\n", 80);
        assertEquals(3, layout.totalRows());
        assertEquals(List.of("ab", "cd", ""), rows(layout));
        assertCursor("ab\ncd\n", 2, 80, 0, 2);
        assertCursor("ab\ncd\n", 3, 80, 1, 0);
        assertCursor("ab\ncd\n", 6, 80, 2, 0);
    }

    @Test
    void longLineSoftWrapsPerCharacter() {
        VisualLayout layout = VisualLayout.of("abcdefghij", 7);
        assertEquals(List.of("abcde", "fghij"), rows(layout));
        assertEquals(2, layout.totalRows());
        assertCursor("abcdefghij", 4, 7, 0, 4);
        // Soft-wrap boundary offsets belong to the following row.
        assertCursor("abcdefghij", 5, 7, 1, 0);
        assertCursor("abcdefghij", 10, 7, 1, 5);
    }

    @Test
    void wordsWrapWholeWhenTheyFitOnFreshRow() {
        // cols 12, prefix 2 → 10 content cells per row (fx's "hello brave world" case).
        VisualLayout layout = VisualLayout.of("hello brave world", 12);
        assertEquals(List.of("hello ", "brave ", "world"), rows(layout));
        assertCursor("hello brave world", 6, 12, 1, 0);
    }

    @Test
    void spacesHangInsteadOfLeadingContinuationRows() {
        // "abcdefghij kl" with 10 content cells: the space hangs past margin.
        VisualLayout layout = VisualLayout.of("abcdefghij kl", 12);
        assertEquals(List.of("abcdefghij ", "kl"), rows(layout));
        assertCursor("abcdefghij kl", 11, 12, 1, 0);
    }

    @Test
    void oversizedWordsStillSplitPerCharacter() {
        VisualLayout layout = VisualLayout.of("ab cdefghijklm", 12);
        assertEquals(List.of("ab cdefghi", "jklm"), rows(layout));
    }

    @Test
    void wideRunesCountTwoCells() {
        VisualLayout layout = VisualLayout.of("a界b", 80);
        assertEquals(List.of("a界b"), rows(layout));
        assertCursor("a界b", 1, 80, 0, 1);
        assertCursor("a界b", 2, 80, 0, 3);
        assertCursor("a界b", 3, 80, 0, 4);
        VisualLayout tight = VisualLayout.of("a界b", 5);
        assertEquals(List.of("a界", "b"), rows(tight));
        // Soft-wrap boundary offset belongs to the following row.
        assertCursor("a界b", 2, 5, 1, 0);
        assertCursor("a界b", 3, 5, 1, 1);
        VisualLayout oversized = VisualLayout.of("界a", 3);
        assertEquals(List.of("界", "a"), rows(oversized));
    }

    @Test
    void combiningMarksAddZeroCells() {
        String text = "a\u0301x";
        VisualLayout layout = VisualLayout.of(text, 80);
        assertEquals(1, layout.totalRows());
        assertCursor(text, 2, 80, 0, 1);
        assertCursor(text, 3, 80, 0, 2);
    }

    @Test
    void cursorBeyondInputClampsToEnd() {
        assertCursor("abc", 99, 80, 0, 3);
    }

    @Test
    void zeroCapacitySplitsOnlyOnHardNewlines() {
        for (int columns : new int[]{0, 1, 2}) {
            VisualLayout layout = VisualLayout.of("abc\ndef", columns);
            assertEquals(2, layout.totalRows(), "columns=" + columns);
            assertEquals(List.of("abc", "def"), rows(layout));
        }
    }

    @Test
    void terminalColumnProjectsPrefixPlusContent() {
        VisualLayout layout = VisualLayout.of("abcd", 80);
        assertEquals(VisualLayout.PROMPT_CELLS + 3 + 1, layout.terminalColumn(layout.cursorAt(3)));
        assertEquals(1, VisualLayout.of("abcd", 0).terminalColumn(layout.cursorAt(3)));
    }

    @Test
    void offsetAtMapsBackToLegalBoundaries() {
        VisualLayout ascii = VisualLayout.of("abcdef", 6);
        assertEquals(0, ascii.offsetAt(0, 0));
        assertEquals(3, ascii.offsetAt(0, 3));
        assertEquals(4, ascii.offsetAt(1, 0));
        assertEquals(6, ascii.offsetAt(1, 9));

        VisualLayout wrapped = VisualLayout.of("abcdef", 6);
        assertEquals(4, wrapped.offsetAt(0, 99), "soft-wrapped row end stays owned by next row");

        VisualLayout wide = VisualLayout.of("a界b", 80);
        assertEquals(1, wide.offsetAt(0, 1), "column inside a wide unit clamps to its start");
        assertEquals(1, wide.offsetAt(0, 2));
        assertEquals(2, wide.offsetAt(0, 3));
        assertEquals(3, wide.offsetAt(0, 4));
    }

    @Test
    void multilinePasteLayoutMatchesFxRowCounts() {
        VisualLayout layout = VisualLayout.of("one two three four five", 12);
        assertEquals(3, layout.totalRows());
        assertEquals(List.of("one two ", "three four ", "five"), rows(layout));
    }

    private static List<String> rows(VisualLayout layout) {
        List<String> slices = new java.util.ArrayList<>();
        for (VisualLayout.Row row : layout.rows()) slices.add(row.slice(layout.text()));
        return slices;
    }
}
