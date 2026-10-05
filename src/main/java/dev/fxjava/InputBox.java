package dev.fxjava;

/**
 * Rounded, muted border around the composer: a cyan chevron on the first row,
 * hanging indent on wrapped rows, and a muted placeholder while empty. The box
 * stops one column short of the margin so the right border never triggers the
 * terminal's pending-wrap state. Layout chars draw even with color disabled.
 */
final class InputBox {
    /** {@code "│ › "} before the text on every row (continuations use spaces). */
    static final int LEFT_CELLS = 4;
    /** {@code " │"} after the padded text. */
    static final int RIGHT_CELLS = 2;
    static final String PLACEHOLDER = "Ask anything · / for commands";

    private InputBox() {
    }

    static int width(int columns) {
        return Math.max(LEFT_CELLS + RIGHT_CELLS + 1, columns - 1);
    }

    /** Wraps composer text to the box interior. */
    static VisualLayout layout(String text, int columns) {
        return VisualLayout.of(text, width(columns) - RIGHT_CELLS, LEFT_CELLS);
    }

    static String top(int columns, Ansi ansi) {
        return ansi.muted() + "╭" + "─".repeat(width(columns) - 2) + "╮" + ansi.reset();
    }

    static String bottom(int columns, Ansi ansi) {
        return ansi.muted() + "╰" + "─".repeat(width(columns) - 2) + "╯" + ansi.reset();
    }

    /** One interior row; {@code segment} must already fit the layout's capacity. */
    static String row(String segment, boolean first, int columns, Ansi ansi) {
        int interior = width(columns) - LEFT_CELLS - RIGHT_CELLS;
        String body = segment;
        int bodyCells = MarkdownConsole.visibleWidth(segment);
        if (first && segment.isEmpty()) {
            String hint = ToolGroupLines.truncate(PLACEHOLDER, interior);
            body = ansi.muted() + hint + ansi.reset();
            bodyCells = MarkdownConsole.visibleWidth(hint);
        }
        String lead = first ? ansi.fg(Ansi.CYAN) + "›" + ansi.reset() : " ";
        return ansi.muted() + "│" + ansi.reset() + " " + lead + " " + body
                + " ".repeat(Math.max(0, interior - bodyCells))
                + " " + ansi.muted() + "│" + ansi.reset();
    }

    /** Transcript echo of a submitted prompt: the chevron without the frame. */
    static String echo(String line, Ansi ansi) {
        return ansi.fg(Ansi.CYAN) + "›" + ansi.reset() + " " + ansi.bold() + line + ansi.reset();
    }
}
