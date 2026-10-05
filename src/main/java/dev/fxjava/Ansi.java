package dev.fxjava;

import java.util.Map;
import java.util.Objects;

/**
 * Minimal ANSI helper. Color sequences no-op when disabled (NO_COLOR or a
 * non-terminal sink); cursor and screen-control sequences always emit because
 * callers only use them against a live terminal.
 */
final class Ansi {
    static final String BLACK = "0";
    static final String RED = "1";
    static final String GREEN = "2";
    static final String YELLOW = "3";
    static final String BLUE = "4";
    static final String MAGENTA = "5";
    static final String CYAN = "6";
    static final String WHITE = "7";

    static final int MUTED = 11;
    static final int SUBTLE = 16;

    private final boolean colorEnabled;

    private Ansi(boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
    }

    static Ansi of(boolean colorEnabled) {
        return new Ansi(colorEnabled);
    }

    static Ansi fromEnvironment(Map<String, String> environment, boolean tty) {
        String noColor = environment.get("NO_COLOR");
        return new Ansi(tty && (noColor == null || noColor.isBlank()));
    }

    String fg(String color) {
        return sgr("3" + Objects.requireNonNull(color));
    }

    String bg(String color) {
        return sgr("4" + Objects.requireNonNull(color));
    }

    String bold() {
        return sgr("1");
    }

    String dim() {
        return sgr("2");
    }

    String italic() {
        return sgr("3");
    }

    /** 256-color grayscale ramp, 0 (near black) through 23 (near white). */
    String gray(int level) {
        return sgr("38;5;" + (232 + Math.max(0, Math.min(23, level))));
    }

    /** Low-contrast foreground for secondary chrome: tool lines, usage, timers. */
    String muted() {
        return gray(MUTED);
    }

    String reset() {
        return sgr("0");
    }

    String eraseLine() {
        return "\u001b[2K";
    }

    String cursorUp(int rows) {
        return rows <= 0 ? "" : "\u001b[" + rows + "A";
    }

    String cursorForward(int columns) {
        return columns <= 0 ? "" : "\u001b[" + columns + "C";
    }

    String clearScreen() {
        return "\u001b[2J\u001b[H";
    }

    String bracketedPaste(boolean enable) {
        return enable ? "\u001b[?2004h" : "\u001b[?2004l";
    }

    private String sgr(String code) {
        return colorEnabled ? "\u001b[" + code + "m" : "";
    }
}
