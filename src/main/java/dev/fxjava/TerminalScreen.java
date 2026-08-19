package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded cell renderer for the plain-output subset a Java pipe can prove. */
final class TerminalScreen {
    private static final int MAX_CELLS = 16_384;

    private TerminalScreen() { }

    static ObjectNode render(ObjectMapper json, String output, int rows, int columns) {
        long count = (long) rows * columns;
        if (rows < 1 || columns < 1 || count > MAX_CELLS || output.indexOf('\u001b') >= 0
                || output.indexOf('\ufffd') >= 0) return null;
        char[][] grid = new char[rows][columns];
        int row = 0;
        int column = 0;
        for (int index = 0; index < output.length(); index++) {
            char value = output.charAt(index);
            if (value == '\r') {
                column = 0;
                continue;
            }
            if (value == '\n') {
                row++;
                column = 0;
                if (row >= rows) row = scroll(grid);
                continue;
            }
            if (value == '\b') {
                if (column > 0) column--;
                continue;
            }
            if (value == '\t') {
                int target = Math.min(columns, ((column / 8) + 1) * 8);
                column = target;
                if (column == columns) {
                    column = 0;
                    row++;
                    if (row >= rows) row = scroll(grid);
                }
                continue;
            }
            if (value < 0x20 || value > 0x7e) return null;
            grid[row][column] = value;
            column++;
            if (column == columns) {
                column = 0;
                row++;
                if (row >= rows) row = scroll(grid);
            }
        }

        ObjectNode snapshot = json.createObjectNode();
        snapshot.putObject("dimensions").put("rows", rows).put("columns", columns);
        snapshot.putObject("cursor").put("row", row).put("column", column)
                .put("visible", true).put("shape", "block").put("blinking", true);
        snapshot.set("modes", modes(json));
        ArrayNode cells = snapshot.putArray("cells");
        for (char[] line : grid) {
            for (char value : line) {
                ObjectNode cell = cells.addObject().put("kind", value == 0 ? "blank" : "single")
                        .put("text", value == 0 ? "" : Character.toString(value));
                cell.set("style", style(json));
            }
        }
        return snapshot;
    }

    private static int scroll(char[][] grid) {
        for (int row = 1; row < grid.length; row++) {
            System.arraycopy(grid[row], 0, grid[row - 1], 0, grid[row].length);
        }
        java.util.Arrays.fill(grid[grid.length - 1], (char) 0);
        return grid.length - 1;
    }

    private static ObjectNode modes(ObjectMapper json) {
        return json.createObjectNode().put("alternate_screen", false).put("origin", false)
                .put("autowrap", true).put("insert", false).put("bracketed_paste", false)
                .put("mouse_tracking", false).put("focus_tracking", false)
                .put("application_cursor_keys", false).put("application_keypad", false)
                .put("keyboard_protocol", false).put("synchronized_updates", false);
    }

    private static ObjectNode style(ObjectMapper json) {
        ObjectNode style = json.createObjectNode();
        style.putObject("foreground").putObject("default");
        style.putObject("background").putObject("default");
        return style.put("bold", false).put("faint", false).put("italic", false)
                .put("underline", false).put("inverse", false).put("strikethrough", false);
    }
}
