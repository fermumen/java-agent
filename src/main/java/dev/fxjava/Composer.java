package dev.fxjava;

/**
 * Multi-line text buffer with a raw cursor position and readline-style edit
 * operations. Pure logic: no I/O, no terminal dependency. Cursor offsets are
 * UTF-16 indices kept on code-point boundaries.
 */
final class Composer {
    private final StringBuilder text = new StringBuilder();
    private int cursor;

    String text() {
        return text.toString();
    }

    int cursor() {
        return cursor;
    }

    boolean isEmpty() {
        return text.length() == 0;
    }

    void clear() {
        text.setLength(0);
        cursor = 0;
    }

    void setText(String value) {
        clear();
        insert(value);
    }

    /** Moves the cursor to a raw offset, clamped to code-point boundaries. */
    void moveTo(int offset) {
        int target = Math.min(Math.max(offset, 0), text.length());
        if (target > 0 && target < text.length() && Character.isLowSurrogate(text.charAt(target))) {
            target--;
        }
        cursor = target;
    }

    /** Inserts at the cursor, splitting pasted multi-line text into hard newlines. */
    void insert(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = KeyDecoder.normalizeNewlines(value);
        text.insert(cursor, normalized);
        cursor += normalized.length();
    }

    void backspace() {
        if (cursor == 0) return;
        int previous = previousCodePointBoundary(cursor);
        text.delete(previous, cursor);
        cursor = previous;
    }

    void deleteForward() {
        if (cursor >= text.length()) return;
        int next = nextCodePointBoundary(cursor);
        text.delete(cursor, next);
    }

    void left() {
        cursor = previousCodePointBoundary(cursor);
    }

    void right() {
        cursor = nextCodePointBoundary(cursor);
    }

    void home() {
        cursor = 0;
    }

    void end() {
        cursor = text.length();
    }

    /** Ctrl+A / Ctrl+E operate on logical lines, matching readline semantics. */
    int lineStart() {
        int newline = text.lastIndexOf("\n", cursor);
        return newline < 0 ? 0 : newline + 1;
    }

    int lineEnd() {
        int newline = text.indexOf("\n", cursor);
        return newline < 0 ? text.length() : newline;
    }

    void moveToLineStart() {
        cursor = lineStart();
    }

    void moveToLineEnd() {
        cursor = lineEnd();
    }

    /** Alt+B / Ctrl+W word boundary scan: whitespace, then non-word run. */
    int wordStartBefore(int from) {
        int index = from;
        while (index > 0 && isWhitespace(text.charAt(index - 1))) index--;
        while (index > 0 && !isWordBreak(text.charAt(index - 1))) index--;
        return index;
    }

    int wordEndAfter(int from) {
        int index = from;
        while (index < text.length() && isWordBreak(text.charAt(index))) index++;
        while (index < text.length() && !isWordBreak(text.charAt(index))) index++;
        return index;
    }

    void moveWordLeft() {
        cursor = wordStartBefore(cursor);
    }

    void moveWordRight() {
        cursor = wordEndAfter(cursor);
    }

    /** Ctrl+K kills from the cursor to end of line; returns the killed text. */
    String killToEndOfLine() {
        int end = lineEnd();
        if (cursor >= end) return "";
        String killed = text.substring(cursor, end);
        text.delete(cursor, end);
        return killed;
    }

    /** Ctrl+U clears from start of line to the cursor; returns the killed text. */
    String killToLineStart() {
        int start = lineStart();
        if (cursor <= start) return "";
        String killed = text.substring(start, cursor);
        text.delete(start, cursor);
        cursor = start;
        return killed;
    }

    /** Ctrl+W / Alt+Backspace kill the word before the cursor; returns the killed text. */
    String killWordBack() {
        int target = wordStartBefore(cursor);
        if (target == cursor) return "";
        String killed = text.substring(target, cursor);
        text.delete(target, cursor);
        cursor = target;
        return killed;
    }

    /** Alt+D kills the word after the cursor; returns the killed text. */
    String killWordForward() {
        int target = wordEndAfter(cursor);
        if (target == cursor) return "";
        String killed = text.substring(cursor, target);
        text.delete(cursor, target);
        return killed;
    }

    private int previousCodePointBoundary(int from) {
        if (from > 1 && Character.isLowSurrogate(text.charAt(from - 1))
                && Character.isHighSurrogate(text.charAt(from - 2))) return from - 2;
        return Math.max(0, from - 1);
    }

    private int nextCodePointBoundary(int from) {
        if (from < text.length() - 1 && Character.isHighSurrogate(text.charAt(from))
                && Character.isLowSurrogate(text.charAt(from + 1))) return from + 2;
        return Math.min(text.length(), from + 1);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isWordBreak(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }
}
