package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Goldens and answer mapping for the inline ask_user_question panel. */
class QuestionUiTest {
    private static final AskUserTool.Question QUESTION = new AskUserTool.Question(
            "Proceed with rollout?", List.of(
                    new AskUserTool.Option("Yes", "deploy now"),
                    new AskUserTool.Option("No", "")));

    @Test
    void panelRendersBoldQuestionNumberedOptionsAndDimDescription() {
        List<String> rows = QuestionUi.panel(Ansi.of(true), 40, QUESTION);
        assertEquals(Arrays.asList(
                "\u001b[1mProceed with rollout?\u001b[0m",
                "  1. Yes",
                "     \u001b[2mdeploy now\u001b[0m",
                "  2. No"), rows);
    }

    @Test
    void panelKeepsLayoutWithoutColor() {
        List<String> rows = QuestionUi.panel(Ansi.of(false), 40, QUESTION);
        assertEquals(Arrays.asList(
                "Proceed with rollout?",
                "  1. Yes",
                "     deploy now",
                "  2. No"), rows);
        for (String row : rows) assertTrue(MarkdownConsole.visibleWidth(row) <= 40);
    }

    @Test
    void digitsMapToOptionIndexes() {
        assertEquals(0, QuestionUi.selectIndex("1", QUESTION.options()));
        assertEquals(1, QuestionUi.selectIndex("2", QUESTION.options()));
        assertEquals(-1, QuestionUi.selectIndex("0", QUESTION.options()));
        assertEquals(-1, QuestionUi.selectIndex("3", QUESTION.options()));
        assertEquals(-1, QuestionUi.selectIndex("-1", QUESTION.options()));
        assertEquals(-1, QuestionUi.selectIndex("99999999999999", QUESTION.options()));
    }

    @Test
    void labelsMatchExactlyOrByCaseInsensitivePrefix() {
        assertEquals(0, QuestionUi.selectIndex("Yes", QUESTION.options()));
        assertEquals(0, QuestionUi.selectIndex("yes", QUESTION.options()));
        assertEquals(0, QuestionUi.selectIndex("y", QUESTION.options()));
        assertEquals(1, QuestionUi.selectIndex("N", QUESTION.options()));
        assertEquals(-1, QuestionUi.selectIndex("", QUESTION.options()));
        assertEquals(-1, QuestionUi.selectIndex("banana", QUESTION.options()));
    }

    @Test
    void digitAnswerChoosesImmediatelyWithOnePrompt() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        String answer = QuestionUi.choose(stream(screen), Ansi.of(true), 40, QUESTION,
                scripted("2"));
        assertEquals("No", answer);
        String shown = screen.toString(StandardCharsets.UTF_8);
        assertTrue(shown.contains("\u001b[1mProceed with rollout?\u001b[0m"));
        assertEquals(1, count(shown, "Choose 1-2: "));
        assertFalse(shown.contains(QuestionUi.WARNING));
    }

    @Test
    void invalidAnswerWarnsDimThenAcceptsPrefixOnRePrompt() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        String answer = QuestionUi.choose(stream(screen), Ansi.of(false), 40, QUESTION,
                scripted("banana", "YE"));
        assertEquals("Yes", answer);
        String shown = screen.toString(StandardCharsets.UTF_8);
        assertEquals(1, count(shown, QuestionUi.WARNING));
        assertEquals(2, count(shown, "Choose 1-2: "));
    }

    @Test
    void styledWarningIsDimmed() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        assertNull(QuestionUi.choose(stream(screen), Ansi.of(true), 40, QUESTION,
                scripted("nope", "also-nope")));
        String shown = screen.toString(StandardCharsets.UTF_8);
        assertTrue(shown.contains("\u001b[2m" + QuestionUi.WARNING + "\u001b[0m"));
        assertEquals(2, count(shown, "Choose 1-2: "));
    }

    @Test
    void endOfInputCancelsWithoutExtraPrompt() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        assertNull(QuestionUi.choose(stream(screen), Ansi.of(false), 40, QUESTION,
                scripted()));
        String shown = screen.toString(StandardCharsets.UTF_8);
        assertEquals(1, count(shown, "Choose 1-2: "));
    }

    private static QuestionUi.AnswerSource scripted(String... lines) {
        ArrayDeque<String> queue = new ArrayDeque<>(Arrays.asList(lines));
        return () -> queue.isEmpty() ? null : queue.poll();
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            found++;
        }
        return found;
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }
}
