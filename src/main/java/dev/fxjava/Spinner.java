package dev.fxjava;

import java.io.PrintStream;

/**
 * Single-line activity indicator: a braille frame, a label with a bright band
 * sweeping across muted gray letters, and elapsed seconds, repainted through
 * carriage return + erase-line so it never leaves residue. Frames and the
 * shimmer position derive from an injected clock so tests drive cadence exactly.
 */
final class Spinner {
    interface Clock {
        long nanoTime();
    }

    static final String THINKING = "Thinking…";
    private static final String FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
    /** Keep the label sweep calm independently of the faster braille frames. */
    private static final long SHIMMER_INTERVAL_NANOS = 200_000_000L;
    /** Gray levels by distance from the shimmer head; anything farther is muted. */
    private static final int[] BAND = {23, 20, 17, 14};
    /** Idle steps between sweeps so the shimmer reads as a pulse, not a scroll. */
    private static final int SWEEP_PAUSE = 6;

    private final PrintStream out;
    private final Ansi ansi;
    private final Clock clock;
    private final long intervalNanos;
    private final String label;
    private boolean active;
    private long startedAt;
    private long lastPaintAt;

    Spinner(PrintStream out, Ansi ansi, Clock clock, long intervalNanos) {
        this(out, ansi, clock, intervalNanos, THINKING);
    }

    Spinner(PrintStream out, Ansi ansi, Clock clock, long intervalNanos, String label) {
        this.out = out;
        this.ansi = ansi;
        this.clock = clock;
        this.intervalNanos = intervalNanos;
        this.label = label;
    }

    synchronized void start() {
        if (active) return;
        active = true;
        startedAt = clock.nanoTime();
        lastPaintAt = startedAt;
        paint(startedAt);
    }

    /** Repaints at most once per interval; safe to call from any poll loop. */
    synchronized void tick() {
        if (!active) return;
        long now = clock.nanoTime();
        if (now - lastPaintAt < intervalNanos) return;
        paint(now);
    }

    /** Erases the line and stops; later ticks and stops emit nothing. */
    synchronized void stop() {
        if (!active) return;
        active = false;
        out.print('\r');
        out.print(ansi.eraseLine());
        out.flush();
    }

    long now() {
        return clock.nanoTime();
    }

    synchronized boolean active() {
        return active;
    }

    private void paint(long now) {
        lastPaintAt = now;
        long elapsed = Math.max(0, now - startedAt);
        long step = elapsed / intervalNanos;
        int frame = (int) (step % FRAMES.length());
        out.print('\r');
        out.print(ansi.eraseLine());
        out.print(ansi.muted() + FRAMES.charAt(frame) + ansi.reset() + " "
                + shimmer(label, elapsed / SHIMMER_INTERVAL_NANOS, ansi) + " "
                + ansi.muted() + duration(elapsed) + ansi.reset());
        out.flush();
    }

    /**
     * Paints each code point by its distance from a head that advances one
     * cell per step, entering from the left and leaving past the right edge.
     */
    static String shimmer(String text, long step, Ansi ansi) {
        if (ansi.reset().isEmpty()) return text;
        int length = text.codePointCount(0, text.length());
        int period = length + 2 * BAND.length + SWEEP_PAUSE;
        int head = (int) (step % period) - BAND.length;
        StringBuilder painted = new StringBuilder();
        int previous = -1;
        int position = 0;
        for (int index = 0; index < text.length(); position++) {
            int codePoint = text.codePointAt(index);
            int distance = Math.abs(position - head);
            int level = distance < BAND.length ? BAND[distance] : Ansi.MUTED;
            if (level != previous) painted.append(ansi.gray(level));
            painted.appendCodePoint(codePoint);
            previous = level;
            index += Character.charCount(codePoint);
        }
        return painted.append(ansi.reset()).toString();
    }

    /** Sub-ten-second spans keep a tenth ({@code 0.4s}) so quick tools still read. */
    static String shortDuration(long nanos) {
        if (nanos < 10_000_000_000L) {
            long tenths = Math.max(0, nanos) / 100_000_000L;
            return tenths / 10 + "." + tenths % 10 + "s";
        }
        return duration(nanos);
    }

    /** {@code 7s} under a minute, then {@code 2m 05s}. */
    static String duration(long nanos) {
        long seconds = nanos / 1_000_000_000L;
        if (seconds < 60) return seconds + "s";
        return seconds / 60 + "m " + String.format("%02d", seconds % 60) + "s";
    }
}
