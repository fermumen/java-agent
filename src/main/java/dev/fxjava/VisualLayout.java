package dev.fxjava;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrap-aware visual row computation for composer text, mirroring fx's input
 * visual_layout.zig: spaces hang past the right margin, words wrap whole when
 * they fit on a fresh row, oversized words split per character, hard newlines
 * always break, and soft-wrap boundary offsets belong to the following row.
 */
final class VisualLayout {
    static final int PROMPT_CELLS = 2;

    private final String text;
    private final int columns;
    private final int prefixCells;
    private final List<Row> rows;

    private VisualLayout(String text, int columns, int prefixCells, List<Row> rows) {
        this.text = text;
        this.columns = columns;
        this.prefixCells = prefixCells;
        this.rows = rows;
    }

    static VisualLayout of(String text, int columns) {
        return of(text, columns, PROMPT_CELLS);
    }

    static VisualLayout of(String text, int columns, int prefixCells) {
        int capacity = Math.max(0, columns - Math.max(prefixCells, 0));
        List<Row> rows = new ArrayList<>();
        int start = 0;
        int width = 0;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\n') {
                rows.add(new Row(start, index, true));
                index++;
                start = index;
                width = 0;
                continue;
            }
            if (c != ' ' && shouldBreakBefore(text, index, capacity, width, start)) {
                rows.add(new Row(start, index, false));
                start = index;
                width = 0;
            }
            width += cellWidth(text, index);
            index++;
        }
        rows.add(new Row(start, text.length(), true));
        return new VisualLayout(text, columns, prefixCells, rows);
    }

    /**
     * Soft wrap: a full row always breaks before the next unit unless it is a
     * hanging space; a word starting near the margin moves whole to the next
     * row when it fits there; words wider than a full row split per character.
     */
    private static boolean shouldBreakBefore(String text, int unitStart, int capacity,
                                             int currentWidth, int rowStart) {
        if (capacity == 0 || currentWidth == 0) return false;
        if (currentWidth >= capacity) return true;
        boolean atWordStart = unitStart == rowStart || isWordBreak(text.charAt(unitStart - 1));
        if (!atWordStart) return false;
        int remaining = capacity - currentWidth;
        int wordWidth = measureWordWidth(text, unitStart, capacity);
        return wordWidth > remaining && wordWidth <= capacity;
    }

    private static boolean isWordBreak(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }

    /** Cell width of the word starting at {@code start}, capped at {@code cap}. */
    static int measureWordWidth(String text, int start, int cap) {
        int width = 0;
        int index = start;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (isWordBreak(c)) break;
            width += cellWidth(text, index);
            if (width > cap) return width;
            index += unitLength(text, index);
        }
        return width;
    }

    List<Row> rows() {
        return rows;
    }

    int totalRows() {
        return rows.size();
    }

    String text() {
        return text;
    }

    /** Cursor position for a raw offset; soft-wrap boundaries resolve to the following row. */
    Position cursorAt(int offset) {
        int target = Math.min(Math.max(offset, 0), text.length());
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (target < row.endExclusive || (target == row.endExclusive && row.ownsEnd())) {
                return new Position(index, contentColumn(row.startOffset, target));
            }
        }
        return new Position(rows.size() - 1, 0);
    }

    /**
     * Maps a visual position back to a legal raw offset: the deepest unit
     * boundary at or before the requested column, or the row start when the
     * column points inside a wide unit. End offsets of soft-wrapped rows stay
     * owned by the following row.
     */
    int offsetAt(int rowIndex, int column) {
        int clamped = Math.min(Math.max(rowIndex, 0), rows.size() - 1);
        Row row = rows.get(clamped);
        int best = row.startOffset;
        int width = 0;
        int index = row.startOffset;
        while (index < row.endExclusive) {
            int unitWidth = cellWidth(text, index);
            if (width + unitWidth > column) break;
            width += unitWidth;
            index += unitLength(text, index);
            best = index;
        }
        if (index >= row.endExclusive && row.ownsEnd()) return row.endExclusive;
        return Math.min(best, row.endExclusive);
    }

    /** Zero-based terminal column (1-based ANSI projection) of a cursor position. */
    int terminalColumn(Position position) {
        if (columns == 0) return 1;
        return Math.min(columns, prefixCells + position.column + 1);
    }

    private int contentColumn(int rowStart, int target) {
        int width = 0;
        int index = rowStart;
        while (index < target) {
            width += cellWidth(text, index);
            index += unitLength(text, index);
        }
        return width;
    }

    static final class Row {
        final int startOffset;
        final int endExclusive;
        final boolean hardEnd;

        Row(int startOffset, int endExclusive, boolean hardEnd) {
            this.startOffset = startOffset;
            this.endExclusive = endExclusive;
            this.hardEnd = hardEnd;
        }

        boolean ownsEnd() {
            return hardEnd;
        }

        String slice(String source) {
            return source.substring(startOffset, endExclusive);
        }
    }

    /** Visual cursor coordinates: zero-based row and content column. */
    static final class Position {
        final int row;
        final int column;

        Position(int row, int column) {
            this.row = row;
            this.column = column;
        }

        int row() {
            return row;
        }

        int column() {
            return column;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Position)) return false;
            Position position = (Position) other;
            return position.row == row && position.column == column;
        }

        @Override
        public int hashCode() {
            return row * 31 + column;
        }

        @Override
        public String toString() {
            return "(" + row + "," + column + ")";
        }
    }

    static int unitLength(String text, int index) {
        char c = text.charAt(index);
        return Character.isHighSurrogate(c) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1)) ? 2 : 1;
    }

    /** Display cells consumed by the code point starting at {@code index}. */
    static int cellWidth(String text, int index) {
        char c = text.charAt(index);
        if (Character.isHighSurrogate(c)) {
            if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                return isWide(Character.toCodePoint(c, text.charAt(index + 1))) ? 2 : 1;
            }
            return 1;
        }
        if (Character.isLowSurrogate(c)) return 0;
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) return 0;
        return isWide(c) ? 2 : 1;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115f)
                || (codePoint >= 0x2e80 && codePoint <= 0x303e)
                || (codePoint >= 0x3041 && codePoint <= 0x33ff)
                || (codePoint >= 0x3400 && codePoint <= 0x4dbf)
                || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
                || (codePoint >= 0xa000 && codePoint <= 0xa4cf)
                || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0xfe30 && codePoint <= 0xfe4f)
                || (codePoint >= 0xff00 && codePoint <= 0xff60)
                || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
                || (codePoint >= 0x20000 && codePoint <= 0x3fffd);
    }
}
