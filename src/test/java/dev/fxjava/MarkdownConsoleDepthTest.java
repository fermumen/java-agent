package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Goldens for the deeper markdown subset: ordered lists, tables, rules, nesting. */
class MarkdownConsoleDepthTest {
    private final MarkdownConsole styled = new MarkdownConsole(Ansi.of(true));
    private final MarkdownConsole plain = new MarkdownConsole(Ansi.of(false));

    @Test
    void orderedListsUseTheirOwnHangingIndent() {
        assertEquals("1. alpha\n   beta\n   gamma", plain.render("1. alpha beta gamma", 12));
        assertEquals("10. alpha\n    beta", plain.render("10. alpha beta", 11));
    }

    @Test
    void orderedListItemsKeepSourceNumbersAndParenDelimiters() {
        assertEquals("3) ship\n4) hold", plain.render("3) ship\n4) hold", 40));
        assertEquals("1. one\n2. two", plain.render("1. one\n2. two", 40));
    }

    @Test
    void orderedItemsRenderNestedEmphasis() {
        assertEquals("• run \u001b[1mx\u001b[0m and \u001b[36my\u001b[0m",
                styled.render("- run **x** and `y`", 80));
        assertEquals("1. \u001b[3mfirst\u001b[0m step", styled.render("1. *first* step", 80));
    }

    @Test
    void boldSpansComposeWithInlineCode() {
        assertEquals("\u001b[1mtotal \u001b[0m\u001b[1m\u001b[36msum\u001b[0m\u001b[1m end\u001b[0m",
                styled.render("**total `sum` end**", 80));
    }

    @Test
    void bareDashRulesRenderDimFullWidth() {
        String rendered = styled.render("above\n\n---\n\nbelow", 10);
        assertEquals("above\n\n\u001b[2m──────────\u001b[0m\n\nbelow", rendered);
        assertFalse(plain.render("---\n\ntext", 8).contains("\u001b"));
        assertEquals("────────\n\ntext", plain.render("---\n\ntext", 8));
    }

    @Test
    void pipeTablesPadColumnsToContentWithinTheWidth() {
        String source = "| Name | Value |\n| --- | --- |\n| alphabet | nine |\n| b | x |";
        String rendered = styled.render(source, 40);
        assertEquals("Name     | Value\n"
                + "\u001b[2m---------+------\u001b[0m\n"
                + "alphabet | nine \n"
                + "b        | x    ", rendered);
    }

    @Test
    void tableCellsShrinkGracefullyInsideNarrowTerminals() {
        String source = "| Name | Notes |\n|---|---|\n| alphabet | extra long text |";
        String rendered = plain.render(source, 20);
        assertEquals("Name     | Notes    \n"
                + "---------+----------\n"
                + "alphabet | extra lo…", rendered);
    }

    @Test
    void tableCellsRenderNestedEmphasisWithoutBreakingAlignment() {
        String source = "| K | V |\n| - | - |\n| **hot** | `cold` |";
        String rendered = styled.render(source, 40);
        String[] lines = stripSgr(rendered).split("\n", -1);
        assertEquals(lines[0].indexOf('|'), lines[2].indexOf('|'), "pipes stay aligned");
        assertTrue(rendered.contains("\u001b[1mhot\u001b[0m"));
        assertTrue(rendered.contains("\u001b[36mcold\u001b[0m"));
    }

    private static String stripSgr(String text) {
        return text.replaceAll("\u001b\\[[0-9]*m", "");
    }

    @Test
    void noColorDropsEveryTableEscapeButKeepsTheGrid() {
        String rendered = plain.render("| A | B |\n|---|---|\n| a | b |", 30);
        assertFalse(rendered.contains("\u001b"));
        assertEquals("A | B\n--+--\na | b", rendered);
    }
}
