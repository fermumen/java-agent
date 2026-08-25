package dev.fxjava;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown-ish assistant renderer over fx's deliberately small ANSI subset:
 * bold ATX headings, fenced code between dim horizontal rules with the
 * language label dim right-aligned, colored inline code, bold and italic
 * spans that compose so emphasis nests inside list items and table cells,
 * hanging-indent bullet and ordered lists, aligned pipe tables with a dim
 * separator row, dim full-width rules, blockquotes behind a dim left bar,
 * and paragraphs wrapped to the terminal width. String-in/string-out so
 * goldens are unit-testable; with color disabled the layout and wrapping are
 * intact.
 */
final class MarkdownConsole {
    private static final String RULE_CHAR = "─";
    private static final String QUOTE_BAR = "│";
    private static final String BULLET = "• ";
    private static final String ELLIPSIS = "…";
    private static final String COLUMN_GAP = " | ";
    private static final String SEPARATOR_GAP = "-+-";
    private static final int QUOTE_CELLS = 2;

    static final int PLAIN = 0;
    private static final int CODE = 1;
    private static final int BOLD = 2;
    private static final int ITALIC = 4;

    private final Ansi ansi;

    MarkdownConsole(Ansi ansi) {
        this.ansi = ansi;
    }

    /** Renders one markdown document to styled lines without a trailing newline. */
    String render(String markdown, int columns) {
        if (markdown == null || markdown.isEmpty()) return "";
        int width = Math.max(1, columns);
        String[] lines = markdown.split("\n", -1);
        List<String> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.length) {
            String stripped = lines[index].strip();
            if (stripped.isEmpty()) {
                index++;
            } else if (isFence(stripped)) {
                index = consumeFence(lines, index, width, blocks);
            } else if (headingLevel(stripped) > 0) {
                blocks.add(renderSpan(stripped.substring(headingLevel(stripped)).strip(),
                        width, BOLD));
                index++;
            } else if (isRule(stripped)) {
                blocks.add(rule(width, ""));
                index++;
            } else if (stripped.startsWith(">")) {
                index = consumeQuote(lines, index, width, blocks);
            } else if (listMarker(stripped) != null) {
                index = consumeList(lines, index, width, blocks);
            } else if (isTableStart(lines, index)) {
                index = consumeTable(lines, index, width, blocks);
            } else {
                index = consumeParagraph(lines, index, width, blocks);
            }
        }
        return String.join("\n\n", blocks);
    }

    private static boolean isFence(String stripped) {
        return stripped.startsWith("```");
    }

    /** Dim full-width horizontal rules: three or more bare dashes. */
    private static boolean isRule(String stripped) {
        return stripped.matches("-{3,}");
    }

    /**
     * Rendered marker for a list row: bullets become the round bullet, ordered
     * items keep their source number and delimiter; null when not a list row.
     */
    private static String listMarker(String stripped) {
        if (stripped.startsWith("- ") || stripped.startsWith("* ")) return BULLET;
        int digits = 0;
        while (digits < stripped.length() && Character.isDigit(stripped.charAt(digits))) digits++;
        if (digits == 0 || digits > 9 || digits >= stripped.length()) return null;
        char delimiter = stripped.charAt(digits);
        if (delimiter != '.' && delimiter != ')') return null;
        if (digits + 1 >= stripped.length() || stripped.charAt(digits + 1) != ' ') return null;
        return stripped.substring(0, digits + 1) + " ";
    }

    /** Counts leading ATX marks; zero when the line is not a well-formed heading. */
    private static int headingLevel(String stripped) {
        int level = 0;
        while (level < stripped.length() && stripped.charAt(level) == '#' && level < 6) level++;
        if (level == 0 || level > 6) return 0;
        if (level < stripped.length() && stripped.charAt(level) != ' '
                && stripped.charAt(level) != '\t') return 0;
        return level;
    }

    private int consumeFence(String[] lines, int start, int width, List<String> blocks) {
        String info = lines[start].strip().replace("`", " ").strip();
        String language = info.isEmpty() ? "" : info.split("\\s+")[0];
        List<String> code = new ArrayList<>();
        int index = start + 1;
        while (index < lines.length && !isFence(lines[index].strip())) {
            code.add(lines[index]);
            index++;
        }
        if (index < lines.length) index++;
        StringBuilder block = new StringBuilder();
        block.append(rule(width, language));
        for (String line : code) block.append('\n').append(line);
        block.append('\n').append(rule(width, ""));
        blocks.add(block.toString());
        return index;
    }

    private int consumeQuote(String[] lines, int start, int width, List<String> blocks) {
        StringBuilder quoted = new StringBuilder();
        int index = start;
        while (index < lines.length) {
            String stripped = lines[index].strip();
            if (!stripped.startsWith(">")) break;
            if (quoted.length() > 0) quoted.append(' ');
            quoted.append(stripped.substring(1).strip());
            index++;
        }
        blocks.add(prefixLines(renderSpan(quoted.toString(), Math.max(1, width - QUOTE_CELLS),
                PLAIN), quotedBar()));
        return index;
    }

    private int consumeList(String[] lines, int start, int width, List<String> blocks) {
        List<String> items = new ArrayList<>();
        int index = start;
        while (index < lines.length) {
            String stripped = lines[index].strip();
            String marker = listMarker(stripped);
            if (marker == null) break;
            int markerCells = visibleWidth(marker);
            items.add(hang(renderSpan(stripped.substring(marker.length()).strip(),
                    Math.max(1, width - markerCells), PLAIN), marker));
            index++;
        }
        blocks.add(String.join("\n", items));
        return index;
    }

    /** Hanging indent: wrapped rows align under the item text, not the marker. */
    private static String hang(String block, String marker) {
        String[] rows = block.split("\n", -1);
        StringBuilder out = new StringBuilder();
        String pad = " ".repeat(visibleWidth(marker));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) out.append('\n');
            out.append(i == 0 ? marker : pad).append(rows[i]);
        }
        return out.toString();
    }

    /** A table starts at a pipe row whose next line is a dash delimiter row. */
    private static boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length) return false;
        String header = lines[index].strip();
        if (!header.contains("|") || splitCells(header).isEmpty()) return false;
        return isDelimiterRow(lines[index + 1].strip());
    }

    private static boolean isDelimiterRow(String stripped) {
        if (!stripped.contains("|")) return false;
        List<String> cells = splitCells(stripped);
        if (cells.isEmpty()) return false;
        for (String cell : cells) {
            if (!cell.replace(":", "").matches("-+")) return false;
        }
        return true;
    }

    /** Splits one pipe row into trimmed cells, tolerating missing outer pipes. */
    private static List<String> splitCells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) cells.add(cell.trim());
        return cells;
    }

    private int consumeTable(String[] lines, int start, int width, List<String> blocks) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(splitCells(lines[start]));
        int index = start + 2;
        while (index < lines.length) {
            String stripped = lines[index].strip();
            if (stripped.isEmpty() || isFence(stripped) || headingLevel(stripped) > 0
                    || !stripped.contains("|")) break;
            rows.add(splitCells(stripped));
            index++;
        }
        blocks.add(renderTable(rows, width));
        return index;
    }

    /**
     * Columns sized to their widest displayed cell, shrunk together until the
     * whole grid fits the terminal width; overflowing cells truncate gracefully.
     */
    private String renderTable(List<List<String>> rows, int width) {
        int columnCount = 0;
        for (List<String> row : rows) columnCount = Math.max(columnCount, row.size());
        int[] widths = new int[Math.max(1, columnCount)];
        for (List<String> row : rows) {
            for (int column = 0; column < widths.length; column++) {
                String cell = column < row.size() ? row.get(column) : "";
                widths[column] = Math.max(widths[column], displayedWidth(cell));
            }
        }
        int overhead = SEPARATOR_GAP.length() * (widths.length - 1);
        int available = Math.max(widths.length, width - overhead);
        while (total(widths) > available) widths[largest(widths)]--;
        List<String> lines = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            lines.add(dataRow(rows.get(r), widths));
            if (r == 0) lines.add(separatorRow(ansi, widths));
        }
        return String.join("\n", lines);
    }

    /** Visible width of a cell as displayed, with inline markup stripped. */
    private static int displayedWidth(String cell) {
        int width = 0;
        for (Unit unit : parseInline(cell == null ? "" : cell, PLAIN)) {
            width += cellWidth(unit.codePoint);
        }
        return width;
    }

    private static int total(int[] widths) {
        int sum = 0;
        for (int width : widths) sum += width;
        return sum;
    }

    private static int largest(int[] widths) {
        int index = 0;
        for (int i = 1; i < widths.length; i++) {
            if (widths[i] > widths[index]) index = i;
        }
        return index;
    }

    private String dataRow(List<String> row, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < widths.length; column++) {
            if (column > 0) line.append(COLUMN_GAP);
            String cell = column < row.size() ? row.get(column) : "";
            int used = fitCell(line, cell, widths[column]);
            int padding = widths[column] - used;
            line.append(" ".repeat(Math.max(0, padding)));
        }
        return line.toString();
    }

    /** Appends one styled cell fitted to the budget with an ellipsis when cut. */
    private int fitCell(StringBuilder line, String cell, int budget) {
        List<Unit> units = parseInline(cell == null ? "" : cell, PLAIN);
        int displayWidth = 0;
        for (Unit unit : units) displayWidth += cellWidth(unit.codePoint);
        int limit = Math.max(1, budget);
        boolean cut = displayWidth > limit;
        if (cut) limit--;
        List<Unit> fitted = new ArrayList<>();
        int used = 0;
        for (Unit unit : units) {
            int cells = cellWidth(unit.codePoint);
            if (used + cells > limit) break;
            fitted.add(unit);
            used += cells;
        }
        line.append(renderLine(fitted));
        if (cut) {
            line.append(ELLIPSIS);
            used++;
        }
        return used;
    }

    /** The dim dash row separating the header from the body. */
    private String separatorRow(Ansi ansi, int[] widths) {
        StringBuilder line = new StringBuilder(ansi.dim());
        for (int column = 0; column < widths.length; column++) {
            if (column > 0) line.append(SEPARATOR_GAP);
            line.append("-".repeat(widths[column]));
        }
        return line.append(ansi.reset()).toString();
    }

    private int consumeParagraph(String[] lines, int start, int width, List<String> blocks) {
        StringBuilder paragraph = new StringBuilder();
        int index = start;
        while (index < lines.length) {
            String stripped = lines[index].strip();
            if (stripped.isEmpty() || isFence(stripped) || headingLevel(stripped) > 0
                    || isRule(stripped) || stripped.startsWith(">") || listMarker(stripped) != null) break;
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(stripped);
            index++;
        }
        blocks.add(renderSpan(paragraph.toString(), width, PLAIN));
        return index;
    }

    /**
     * Parses inline markup into styled units, wraps them greedily by visible
     * width, and emits one ANSI-styled string per row without trailing newline.
     */
    String renderSpan(String text, int columns, int forced) {
        List<Unit> units = parseInline(text, forced);
        StringBuilder block = new StringBuilder();
        boolean first = true;
        for (List<Unit> line : wrapUnits(units, Math.max(1, columns))) {
            if (!first) block.append('\n');
            first = false;
            block.append(renderLine(line));
        }
        return block.toString();
    }

    private String quotedBar() {
        return ansi.dim() + QUOTE_BAR + ansi.reset() + " ";
    }

    /** Dim horizontal rule; a non-empty label sits dim right-aligned on the rule. */
    private String rule(int width, String label) {
        if (label.isEmpty()) return ansi.dim() + RULE_CHAR.repeat(width) + ansi.reset();
        int labelCells = visibleWidth(label);
        int dashes = Math.max(1, width - labelCells - 1);
        return ansi.dim() + RULE_CHAR.repeat(dashes) + " " + label + ansi.reset();
    }

    private String openSequence(int style) {
        StringBuilder sequence = new StringBuilder();
        if ((style & BOLD) != 0) sequence.append(ansi.bold());
        if ((style & ITALIC) != 0) sequence.append(ansi.italic());
        if ((style & CODE) != 0) sequence.append(ansi.fg(Ansi.CYAN));
        return sequence.toString();
    }

    static final class Unit {
        final int codePoint;
        final int style;

        Unit(int codePoint, int style) {
            this.codePoint = codePoint;
            this.style = style;
        }
    }

    private static final class Run {
        final String text;
        final int style;

        Run(String text, int style) {
            this.text = text;
            this.style = style;
        }
    }

    /** Parses one span into per-codepoint styled units; emphasis composes. */
    static List<Unit> parseInline(String text, int base) {
        String source = text == null ? "" : text;
        List<Run> runs = new ArrayList<>();
        collectRuns(runs, source, 0, source.length(), base);
        List<Unit> units = new ArrayList<>();
        for (Run run : runs) {
            for (int i = 0; i < run.text.length(); ) {
                int codePoint = run.text.codePointAt(i);
                units.add(new Unit(codePoint, run.style));
                i += Character.charCount(codePoint);
            }
        }
        return units;
    }

    /** Walks one bracketing scope, recursing into bold/italic/code interiors. */
    private static void collectRuns(List<Run> runs, String text, int start, int end, int base) {
        int index = start;
        int literalStart = start;
        while (index < end) {
            char c = text.charAt(index);
            if (c == '`') {
                int close = text.indexOf('`', index + 1);
                if (close > index && close < end) {
                    push(runs, text.substring(literalStart, index), base);
                    runs.add(new Run(text.substring(index + 1, close), base | CODE));
                    index = close + 1;
                    literalStart = index;
                    continue;
                }
            } else if (c == '*' && index + 1 < end && text.charAt(index + 1) == '*') {
                int close = text.indexOf("**", index + 2);
                if (close >= 0 && close < end) {
                    push(runs, text.substring(literalStart, index), base);
                    collectRuns(runs, text, index + 2, close, base | BOLD);
                    index = close + 2;
                    literalStart = index;
                    continue;
                }
            } else if (c == '*' || c == '_') {
                int close = text.indexOf(c, index + 1);
                if (close > index && close < end) {
                    push(runs, text.substring(literalStart, index), base);
                    collectRuns(runs, text, index + 1, close, base | ITALIC);
                    index = close + 1;
                    literalStart = index;
                    continue;
                }
            }
            index++;
        }
        push(runs, text.substring(literalStart, end), base);
    }

    private static void push(List<Run> runs, String text, int style) {
        if (!text.isEmpty()) runs.add(new Run(text, style));
    }

    /** Greedy word wrap over styled units; oversized words split per unit. */
    static List<List<Unit>> wrapUnits(List<Unit> units, int capacity) {
        List<List<Unit>> lines = new ArrayList<>();
        List<Unit> line = new ArrayList<>();
        int lineWidth = 0;
        List<Unit> queuedSpaces = new ArrayList<>();
        int index = 0;
        while (index < units.size()) {
            if (units.get(index).codePoint == ' ') {
                queuedSpaces.add(units.get(index));
                index++;
                continue;
            }
            int wordEnd = index;
            int wordWidth = 0;
            while (wordEnd < units.size() && units.get(wordEnd).codePoint != ' ') {
                wordWidth += cellWidth(units.get(wordEnd).codePoint);
                wordEnd++;
            }
            int queuedWidth = queuedSpaces.size();
            boolean breakBefore = !line.isEmpty()
                    && lineWidth + queuedWidth + wordWidth > capacity;
            if (breakBefore) {
                lines.add(line);
                line = new ArrayList<>();
                lineWidth = 0;
                queuedSpaces.clear();
                queuedWidth = 0;
            }
            if (wordWidth > capacity) {
                for (int i = index; i < wordEnd; i++) {
                    int unitCells = cellWidth(units.get(i).codePoint);
                    if (lineWidth > 0 && lineWidth + unitCells > capacity) {
                        lines.add(line);
                        line = new ArrayList<>();
                        lineWidth = 0;
                    }
                    line.add(units.get(i));
                    lineWidth += unitCells;
                }
            } else {
                line.addAll(queuedSpaces);
                for (int i = index; i < wordEnd; i++) line.add(units.get(i));
                lineWidth += queuedWidth + wordWidth;
            }
            queuedSpaces.clear();
            index = wordEnd;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    private String renderLine(List<Unit> line) {
        StringBuilder out = new StringBuilder();
        int current = PLAIN;
        StringBuilder run = new StringBuilder();
        for (Unit unit : line) {
            if (unit.style != current) {
                flushRun(out, run, current);
                run = new StringBuilder();
                current = unit.style;
            }
            run.appendCodePoint(unit.codePoint);
        }
        flushRun(out, run, current);
        return out.toString();
    }

    private void flushRun(StringBuilder out, StringBuilder run, int style) {
        if (run.length() == 0) return;
        String open = openSequence(style);
        if (open.isEmpty()) out.append(run);
        else out.append(open).append(run).append(ansi.reset());
    }

    private static String prefixLines(String block, String prefix) {
        String[] lines = block.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(prefix).append(lines[i]);
        }
        return out.toString();
    }

    static int visibleWidth(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            width += cellWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    private static int cellWidth(int codePoint) {
        char[] encoded = Character.toChars(codePoint);
        return VisualLayout.cellWidth(new String(encoded), 0);
    }
}
