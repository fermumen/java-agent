package dev.fxjava;

import java.util.Objects;

/** Immutable decoded terminal key event; central seam for all input handling. */
final class KeyEvent {
    enum Kind {
        ENTER, BACKSPACE, DELETE, UP, DOWN, LEFT, RIGHT, HOME, END,
        PAGE_UP, PAGE_DOWN, TAB, SHIFT_TAB, ESCAPE, DOUBLE_ESCAPE,
        CTRL_CHAR, ALT_CHAR, TEXT, PASTE_START, PASTE_END, UNKNOWN
    }

    private final Kind kind;
    private final char key;
    private final String text;

    private KeyEvent(Kind kind, char key, String text) {
        this.kind = kind;
        this.key = key;
        this.text = text == null ? "" : text;
    }

    static KeyEvent enter() {
        return new KeyEvent(Kind.ENTER, '\0', null);
    }

    static KeyEvent backspace() {
        return new KeyEvent(Kind.BACKSPACE, '\0', null);
    }

    static KeyEvent delete() {
        return new KeyEvent(Kind.DELETE, '\0', null);
    }

    static KeyEvent of(Kind kind) {
        return new KeyEvent(kind, '\0', null);
    }

    static KeyEvent ctrl(char letter) {
        return new KeyEvent(Kind.CTRL_CHAR, Character.toLowerCase(letter), null);
    }

    static KeyEvent alt(char letter) {
        return new KeyEvent(Kind.ALT_CHAR, Character.toLowerCase(letter), null);
    }

    static KeyEvent altBackspace() {
        return new KeyEvent(Kind.ALT_CHAR, '\u007f', null);
    }

    static KeyEvent text(String value) {
        Objects.requireNonNull(value);
        if (value.isEmpty()) throw new IllegalArgumentException("empty text event");
        return new KeyEvent(Kind.TEXT, '\0', value);
    }

    Kind kind() {
        return kind;
    }

    /** Control or alt key payload for {@link Kind#CTRL_CHAR}/{@link Kind#ALT_CHAR}. */
    char key() {
        return key;
    }

    /** Decoded UTF-8 payload for {@link Kind#TEXT}. */
    String text() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof KeyEvent)) return false;
        KeyEvent event = (KeyEvent) other;
        return kind == event.kind && key == event.key && text.equals(event.text);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * kind.hashCode() + key) + text.hashCode();
    }

    @Override
    public String toString() {
        if (kind == Kind.TEXT) return "TEXT(" + text + ")";
        if (kind == Kind.CTRL_CHAR || kind == Kind.ALT_CHAR) return kind + "(" + nameOf(key) + ")";
        return kind.name();
    }

    private static String nameOf(char key) {
        return key == '\u007f' ? "backspace" : String.valueOf(key);
    }
}
