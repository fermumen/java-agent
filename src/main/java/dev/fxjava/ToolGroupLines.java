package dev.fxjava;

/**
 * Single-line compositions for live tool groups: a muted running bullet that
 * is later rewritten in place as a check or cross. Name, preview, and duration
 * stay in low-contrast grays so tool chatter recedes behind the answer text.
 */
final class ToolGroupLines {
    private static final String ELLIPSIS = "…";
    private static final String MARK_RUNNING = "●";
    private static final String MARK_OK = "✓";
    private static final String MARK_FAILED = "✗";

    private ToolGroupLines() {
    }

    static String running(String name, String preview, int columns, Ansi ansi) {
        return ansi.muted() + MARK_RUNNING + ansi.reset() + " "
                + fit(name, preview, "", Math.max(1, columns - 2), ansi);
    }

    static String completed(String name, String preview, boolean error, int columns, Ansi ansi) {
        return completed(name, preview, "", error, columns, ansi);
    }

    /** {@code elapsed} trails the preview, e.g. {@code 1.2s}; blank omits it. */
    static String completed(String name, String preview, String elapsed, boolean error,
                            int columns, Ansi ansi) {
        String mark = error
                ? ansi.fg(Ansi.RED) + MARK_FAILED + ansi.reset()
                : ansi.fg(Ansi.GREEN) + MARK_OK + ansi.reset();
        return mark + " " + fit(name, preview, elapsed, Math.max(1, columns - 2), ansi);
    }

    /**
     * Name first, then the muted preview, then the elapsed suffix; the suffix
     * is reserved before the preview truncates so durations never get cut.
     */
    private static String fit(String name, String preview, String elapsed, int budget, Ansi ansi) {
        String suffix = elapsed == null || elapsed.isBlank() ? "" : " · " + elapsed;
        int suffixWidth = MarkdownConsole.visibleWidth(suffix);
        if (suffixWidth > budget / 2) {
            suffix = "";
            suffixWidth = 0;
        }
        String flatName = truncate(flatten(name), budget - suffixWidth);
        int used = MarkdownConsole.visibleWidth(flatName) + suffixWidth;
        String styled = ansi.gray(Ansi.SUBTLE) + flatName + ansi.reset();
        String flatPreview = preview == null ? "" : truncate(flatten(preview), budget - used - 1);
        if (!flatPreview.isEmpty()) styled += " " + ansi.muted() + flatPreview + ansi.reset();
        if (!suffix.isEmpty()) styled += ansi.muted() + suffix + ansi.reset();
        return styled;
    }

    private static String flatten(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("\\s+", " ");
    }

    static String truncate(String text, int maxCells) {
        if (maxCells <= 0) return "";
        if (MarkdownConsole.visibleWidth(text) <= maxCells) return text;
        StringBuilder cut = new StringBuilder();
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            int cells = VisualLayout.cellWidth(text, index);
            if (width + cells > maxCells - 1) break;
            cut.appendCodePoint(codePoint);
            width += cells;
            index += Character.charCount(codePoint);
        }
        return cut.append(ELLIPSIS).toString();
    }
}
