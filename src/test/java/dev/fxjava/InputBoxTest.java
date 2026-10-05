package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bordered composer geometry: every row spans the box, one column short of the margin. */
class InputBoxTest {
    private final Ansi plain = Ansi.of(false);

    @Test
    void emptyComposerShowsChevronAndPlaceholder() {
        assertEquals("╭" + "─".repeat(37) + "╮", InputBox.top(40, plain));
        assertEquals("│ › Ask anything · / for commands     │", InputBox.row("", true, 40, plain));
        assertEquals("╰" + "─".repeat(37) + "╯", InputBox.bottom(40, plain));
    }

    @Test
    void wrappedRowsHangUnderTheChevronAndStayInsideTheBorder() {
        String text = "alpha beta gamma delta epsilon zeta eta theta";
        VisualLayout layout = InputBox.layout(text, 30);
        assertTrue(layout.rows().size() > 1);
        for (int index = 0; index < layout.rows().size(); index++) {
            VisualLayout.Row row = layout.rows().get(index);
            String segment = text.substring(row.startOffset, row.endExclusive).stripTrailing();
            String line = InputBox.row(segment, index == 0, 30, plain);
            assertEquals(29, MarkdownConsole.visibleWidth(line), line);
            assertTrue(line.startsWith(index == 0 ? "│ › " : "│   "), line);
            assertTrue(line.endsWith(" │"), line);
        }
    }

    @Test
    void colorWrapsBordersInMutedGrayAndTheChevronInCyan() {
        String row = InputBox.row("hi", true, 20, Ansi.of(true));
        assertTrue(row.startsWith("\u001b[38;5;243m│\u001b[0m \u001b[36m›\u001b[0m hi"), row);
        assertEquals("\u001b[36m›\u001b[0m \u001b[1mhi\u001b[0m", InputBox.echo("hi", Ansi.of(true)));
    }
}
