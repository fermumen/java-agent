package dev.fxjava;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure composition and answer mapping for the inline ask_user_question
 * panel: bold question text, numbered options whose descriptions hang dim
 * beneath the label, and one dim re-prompt before a batch cancels. Answers
 * are digits or case-insensitive label prefixes; everything is string-in/
 * string-out so goldens stay unit-testable without a terminal.
 */
final class QuestionUi {
    static final String WARNING = "Not an option. Answer with 1-N or an option prefix.";

    private QuestionUi() {
    }

    /** Source of committed raw-mode answer lines; null cancels or ends input. */
    interface AnswerSource {
        String next() throws IOException, InterruptedException;
    }

    /**
     * Renders the panel, then reads at most two answers: an invalid first
     * answer earns the dim warning and one re-prompt, a second invalid
     * answer cancels. Returns the chosen label, or null on cancel.
     */
    static String choose(PrintStream out, Ansi ansi, int columns,
                         AskUserTool.Question question, AnswerSource answers)
            throws IOException, InterruptedException {
        out.print('\n');
        for (String row : panel(ansi, columns, question)) out.println(row);
        out.flush();
        int optionCount = question.options().size();
        for (int attempt = 0; attempt < 2; attempt++) {
            out.print(prompt(optionCount));
            out.flush();
            String line = answers.next();
            if (line == null) return null;
            int index = selectIndex(line.trim(), question.options());
            if (index >= 0) return question.options().get(index).label();
            if (attempt == 0) {
                out.println(ansi.dim() + WARNING + ansi.reset());
                out.flush();
            }
        }
        return null;
    }

    /** Styled panel rows: bold question, then numbered options with dim descriptions. */
    static List<String> panel(Ansi ansi, int columns, AskUserTool.Question question) {
        List<String> rows = new ArrayList<>();
        rows.add(ansi.bold() + ToolGroupLines.truncate(question.text(), Math.max(1, columns))
                + ansi.reset());
        for (int index = 0; index < question.options().size(); index++) {
            AskUserTool.Option option = question.options().get(index);
            String number = Integer.toString(index + 1);
            int indentCells = 2 + number.length() + 2;
            rows.add("  " + number + ". "
                    + ToolGroupLines.truncate(option.label(), Math.max(1, columns - indentCells)));
            if (!option.description().isBlank()) {
                String description = ToolGroupLines.truncate(option.description(),
                        Math.max(1, columns - indentCells));
                rows.add(" ".repeat(indentCells) + ansi.dim() + description + ansi.reset());
            }
        }
        return rows;
    }

    static String prompt(int optionCount) {
        return "Choose 1-" + optionCount + ": ";
    }

    /** Digits 1..N win; otherwise the first case-insensitive label prefix matches. */
    static int selectIndex(String input, List<AskUserTool.Option> options) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) return -1;
        boolean digits = true;
        for (int index = 0; index < raw.length(); index++) {
            if (!Character.isDigit(raw.charAt(index))) digits = false;
        }
        if (digits) {
            try {
                int number = Integer.parseInt(raw);
                if (number >= 1 && number <= options.size()) return number - 1;
            } catch (NumberFormatException oversized) {
                return -1;
            }
            return -1;
        }
        for (int index = 0; index < options.size(); index++) {
            if (options.get(index).label().regionMatches(true, 0, raw, 0, raw.length())) {
                return index;
            }
        }
        return -1;
    }
}
