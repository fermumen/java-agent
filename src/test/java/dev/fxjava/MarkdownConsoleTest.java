package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Goldens for the assistant markdown renderer across the styled and NO_COLOR paths. */
class MarkdownConsoleTest {
    private final MarkdownConsole styled = new MarkdownConsole(Ansi.of(true));
    private final MarkdownConsole plain = new MarkdownConsole(Ansi.of(false));

    @Test
    void headingsRenderBold() {
        assertEquals("\u001b[1mTitle\u001b[0m", styled.render("# Title", 80));
        assertEquals("\u001b[1mDeep\u001b[0m", styled.render("### Deep", 80));
    }

    @Test
    void fencedCodeSitsBetweenDimRulesWithRightAlignedLanguageLabel() {
        String rendered = styled.render("```java\nint x = 1;\n```", 20);
        assertEquals("\u001b[2m─────────────── java\u001b[0m\n"
                + "int x = 1;\n"
                + "\u001b[2m────────────────────\u001b[0m", rendered);
    }

    @Test
    void fencedCodeWithoutLanguageUsesFullRules() {
        String rendered = styled.render("```\nplain\n```", 10);
        assertEquals("\u001b[2m──────────\u001b[0m\nplain\n\u001b[2m──────────\u001b[0m", rendered);
    }

    @Test
    void inlineCodeBoldAndItalicSpansAreStyled() {
        String rendered = styled.render("run `x=1` and **bold** and *sl* now", 200);
        assertEquals("run \u001b[36mx=1\u001b[0m and \u001b[1mbold\u001b[0m and \u001b[3msl\u001b[0m now",
                rendered);
    }

    @Test
    void bulletsUseHangingIndent() {
        String rendered = styled.render("- alpha beta gamma delta", 12);
        assertEquals("• alpha beta\n  gamma\n  delta", rendered);
    }

    @Test
    void paragraphsWrapToTerminalWidth() {
        String rendered = plain.render("the quick brown fox jumps over the lazy dog", 12);
        assertEquals("the quick\nbrown fox\njumps over\nthe lazy dog", rendered);
    }

    @Test
    void paragraphSourceLinesMergeBeforeWrapping() {
        assertEquals("hello world second line", plain.render("hello world\nsecond line", 80));
    }

    @Test
    void oversizedWordsSplitPerCharacter() {
        assertEquals("abcdef\nghijkl\nmnop", plain.render("abcdefghijklmnop", 6));
    }

    @Test
    void blockquotesCarryADimLeftBar() {
        assertEquals("\u001b[2m│\u001b[0m said text", styled.render("> said text", 40));
    }

    @Test
    void blankLinesSeparateBlocks() {
        assertEquals("\u001b[1mH\u001b[0m\n\ntext here", styled.render("# H\n\ntext here", 80));
    }

    @Test
    void noColorKeepsLayoutAndDropsEveryEscape() {
        String rendered = plain.render("# Hi\n\n- one `x` two\n\n> quote\n\n```\ncode\n```", 40);
        assertFalse(rendered.contains("\u001b"));
        String fullRule = "─".repeat(40);
        assertEquals("Hi\n\n• one x two\n\n│ quote\n\n" + fullRule + "\ncode\n" + fullRule,
                rendered);
    }
}
