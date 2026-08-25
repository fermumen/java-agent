package dev.fxjava;

/**
 * Single-line compositions for live tool groups: a dim running bullet that is
 * later rewritten in place as a check or cross plus the dim truncated preview.
 */
final class ToolGroupLines {
    private static final String ELLIPSIS = "…";
    private static final String MARK_RUNNING = "●";
    private static final String MARK_OK = "✓";
    private static final String MARK_FAILED = "✗";

    private ToolGroupLines() {
    }

    static String running(String name, String preview, int columns, Ansi ansi) {
        return ansi.dim() + MARK_RUNNING + ansi.reset() + " "
                + fit(name, preview, Math.max(1, columns - 2), ansi);
    }

    static String completed(String name, String preview, boolean error, int columns, Ansi ansi) {
        String mark = error
                ? ansi.fg(Ansi.RED) + MARK_FAILED + ansi.reset()
                : ansi.fg(Ansi.GREEN) + MARK_OK + ansi.reset();
        return mark + " " + fit(name, preview, Math.max(1, columns - 2), ansi);
    }

    /** Name first, then the dim preview; both flattened and bounded to the budget. */
    private static String fit(String name, String preview, int budget, Ansi ansi) {
        String flatName = truncate(flatten(name), budget);
        int used = MarkdownConsole.visibleWidth(flatName);
        if (preview == null || preview.isBlank()) return flatName;
        String flatPreview = truncate(flatten(preview), budget - used - 1);
        if (flatPreview.isEmpty()) return flatName;
        return flatName + " " + ansi.dim() + flatPreview + ansi.reset();
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
