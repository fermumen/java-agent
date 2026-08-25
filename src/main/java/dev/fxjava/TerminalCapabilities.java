package dev.fxjava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Terminal capability probe: TTY presence, color support, and size discovery. */
final class TerminalCapabilities {
    private final boolean tty;
    private final boolean color;
    private final Size size;
    private final SizeSource sizeSource;

    private TerminalCapabilities(boolean tty, boolean color, Size size, SizeSource sizeSource) {
        this.tty = tty;
        this.color = color;
        this.size = size;
        this.sizeSource = sizeSource;
    }

    static TerminalCapabilities detect(Map<String, String> environment) {
        return detect(environment, System.console() != null);
    }

    static TerminalCapabilities detect(Map<String, String> environment, boolean consolePresent) {
        return detect(environment, consolePresent,
                consolePresent && !"dumb".equals(environment.get("TERM"))
                        ? new SttySizeSource() : SizeSource.unavailable());
    }

    static TerminalCapabilities detect(Map<String, String> environment, boolean consolePresent,
                                       SizeSource sizeSource) {
        boolean tty = consolePresent && !"dumb".equals(environment.get("TERM"));
        return new TerminalCapabilities(tty, colorEnabled(environment, tty),
                sizeSource.query(), sizeSource);
    }

    private static boolean colorEnabled(Map<String, String> environment, boolean tty) {
        if (!tty) return false;
        String noColor = environment.get("NO_COLOR");
        return noColor == null || noColor.isBlank();
    }

    boolean interactive() {
        return tty;
    }

    boolean color() {
        return color;
    }

    Size size() {
        Size current = sizeSource.query();
        return current.known() ? current : size;
    }

    interface SizeSource {
        Size query();

        static SizeSource unavailable() {
            return () -> Size.UNKNOWN;
        }
    }

    static final class Size {
        static final Size UNKNOWN = new Size(-1, -1);
        static final Size FALLBACK = new Size(24, 80);

        final int rows;
        final int columns;

        Size(int rows, int columns) {
            this.rows = rows;
            this.columns = columns;
        }

        boolean known() {
            return rows > 0 && columns > 0;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Size)) return false;
            Size size = (Size) other;
            return size.rows == rows && size.columns == columns;
        }

        @Override
        public int hashCode() {
            return rows * 31 + columns;
        }

        @Override
        public String toString() {
            return known() ? rows + "x" + columns : "unknown";
        }
    }

    /** Shells out to {@code stty size}; never throws and yields {@link #UNKNOWN} when unavailable. */
    static final class SttySizeSource implements SizeSource {
        @Override
        public Size query() {
            try {
                ProcessBuilder builder = new ProcessBuilder("stty", "size")
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = builder.start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return Size.UNKNOWN;
                }
                if (process.exitValue() != 0) return Size.UNKNOWN;
                byte[] output = process.getInputStream().readAllBytes();
                process.waitFor();
                return parse(new String(output, StandardCharsets.UTF_8).trim());
            } catch (IOException | InterruptedException unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                return Size.UNKNOWN;
            }
        }
    }

    static Size parse(String sttyOutput) {
        if (sttyOutput == null || sttyOutput.isBlank()) return Size.UNKNOWN;
        String[] parts = sttyOutput.trim().split("\\s+");
        if (parts.length != 2) return Size.UNKNOWN;
        try {
            int rows = Integer.parseInt(parts[0]);
            int columns = Integer.parseInt(parts[1]);
            if (rows <= 0 || columns <= 0) return Size.UNKNOWN;
            return new Size(rows, columns);
        } catch (NumberFormatException malformed) {
            return Size.UNKNOWN;
        }
    }
}
