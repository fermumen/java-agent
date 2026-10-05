package dev.fxjava;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inline approval prompt for the raw terminal, styled after fx's approval
 * footer: tool name bold in a bordered box with the preview wrapped inside
 * (long previews collapse after a few rows into a muted count) and
 * the option legend on the bottom border. Decision mapping and grant-key
 * normalization are pure so they stay unit-testable without a terminal.
 */
final class ApprovalPrompt {
    enum Decision {
        YES, NO, ALWAYS;

        String reply() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String OPTIONS = "y yes · n no · a always this session";
    /** Preview rows shown before the rest collapses into one muted count row. */
    static final int MAX_PREVIEW_ROWS = 6;

    private ApprovalPrompt() {
    }

    /**
     * Maps a decoded key to a decision: y/yes, n/no, a/always; Enter and Esc
     * deny. Returns null for keys that should be ignored.
     */
    static Decision decisionOf(KeyEvent event) {
        if (event.kind() == KeyEvent.Kind.ENTER || event.kind() == KeyEvent.Kind.ESCAPE) {
            return Decision.NO;
        }
        if (event.kind() != KeyEvent.Kind.TEXT) return null;
        String text = event.text().trim().toLowerCase(Locale.ROOT);
        if (text.length() != 1) return null;
        switch (text.charAt(0)) {
            case 'y': return Decision.YES;
            case 'n': return Decision.NO;
            case 'a': return Decision.ALWAYS;
            default: return null;
        }
    }

    /** Stable identity of a request: exact tool name plus canonical argument JSON. */
    static String grantKey(String tool, String canonicalArguments) {
        return tool + "\u0000" + canonicalArguments;
    }

    static String flatten(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("\\s+", " ");
    }

    /** Rendered rows of the box; {@code lines} excludes the leading newline. */
    static final class Box {
        final int rows;
        private final List<String> lines;

        Box(List<String> lines) {
            this.lines = List.copyOf(lines);
            this.rows = lines.size();
        }

        List<String> lines() {
            return lines;
        }
    }

    /**
     * Prints the boxed prompt starting on the line after the cursor: the
     * caller's running-tool line stays intact above it.
     */
    static Box render(PrintStream out, Ansi ansi, int columns, String tool, String preview) {
        int width = Math.max(20, columns);
        String safeTool = ToolGroupLines.truncate(flatten(tool), Math.max(1, width - 14));
        int toolCells = MarkdownConsole.visibleWidth(safeTool);
        List<String> lines = new ArrayList<>();
        StringBuilder top = new StringBuilder();
        top.append("┌─ Allow ").append(ansi.bold()).append(safeTool).append(ansi.reset()).append("? ");
        int dashes = Math.max(1, width - 12 - toolCells);
        top.append("─".repeat(dashes)).append("┐");
        lines.add(top.toString());
        List<String> rows = wrap(flatten(preview), Math.max(1, width - 4));
        int hidden = rows.size() > MAX_PREVIEW_ROWS ? rows.size() - (MAX_PREVIEW_ROWS - 1) : 0;
        for (String row : hidden > 0 ? rows.subList(0, MAX_PREVIEW_ROWS - 1) : rows) {
            lines.add(boxRow(row, row, width));
        }
        if (hidden > 0) {
            String more = ToolGroupLines.truncate("… " + hidden + " more rows", Math.max(1, width - 4));
            lines.add(boxRow(ansi.muted() + more + ansi.reset(), more, width));
        }
        String options = ToolGroupLines.truncate(OPTIONS, Math.max(1, width - 6));
        int optionsCells = MarkdownConsole.visibleWidth(options);
        int tail = Math.max(1, width - 5 - optionsCells);
        lines.add("└─ " + options + " " + "─".repeat(tail) + "┘");
        out.print('\n');
        for (String line : lines) out.println(line);
        return new Box(lines);
    }

    /** One bordered row; {@code plain} is the unstyled text used for padding. */
    private static String boxRow(String styled, String plain, int width) {
        int padding = Math.max(0, width - 4 - MarkdownConsole.visibleWidth(plain));
        return "│ " + styled + " ".repeat(padding) + " │";
    }

    /**
     * Erases every box row, then returns to the start of the row the box was
     * opened beneath, so the caller can rewrite its running-tool line in place.
     */
    static void erase(PrintStream out, Ansi ansi, Box box) {
        StringBuilder cleanup = new StringBuilder();
        cleanup.append(ansi.cursorUp(box.rows));
        for (int index = 0; index < box.rows; index++) {
            cleanup.append('\r').append(ansi.eraseLine());
            if (index < box.rows - 1) cleanup.append('\n');
        }
        cleanup.append('\r').append(ansi.eraseLine());
        cleanup.append(ansi.cursorUp(box.rows)).append('\r');
        out.print(cleanup);
    }

    private static List<String> wrap(String text, int width) {
        List<String> rows = new ArrayList<>();
        if (text.isEmpty()) return rows;
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            int wordCells = MarkdownConsole.visibleWidth(word);
            int rowCells = MarkdownConsole.visibleWidth(row.toString());
            if (row.length() > 0 && rowCells + 1 + wordCells > width) {
                rows.add(row.toString());
                row.setLength(0);
                while (wordCells > width) {
                    String head = ToolGroupLines.truncate(word, width - 1);
                    rows.add(head);
                    word = word.substring(head.length() - 1);
                    wordCells = MarkdownConsole.visibleWidth(word);
                }
            }
            if (row.length() > 0) row.append(' ');
            row.append(word);
        }
        if (row.length() > 0) rows.add(row.toString());
        return rows;
    }
}
