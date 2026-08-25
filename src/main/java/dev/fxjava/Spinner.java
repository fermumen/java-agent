package dev.fxjava;

import java.io.PrintStream;

/**
 * Single-line activity indicator: a braille frame plus elapsed seconds,
 * repainted through carriage return + erase-line so it never leaves residue.
 * Frames derive from an injected clock so tests can drive cadence exactly.
 */
final class Spinner {
    interface Clock {
        long nanoTime();
    }

    private static final String FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    private final PrintStream out;
    private final Ansi ansi;
    private final Clock clock;
    private final long intervalNanos;
    private boolean active;
    private long startedAt;
    private long lastPaintAt;

    Spinner(PrintStream out, Ansi ansi, Clock clock, long intervalNanos) {
        this.out = out;
        this.ansi = ansi;
        this.clock = clock;
        this.intervalNanos = intervalNanos;
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

    synchronized boolean active() {
        return active;
    }

    private void paint(long now) {
        lastPaintAt = now;
        long elapsed = Math.max(0, now - startedAt);
        int frame = (int) ((elapsed / intervalNanos) % FRAMES.length());
        out.print('\r');
        out.print(ansi.eraseLine());
        out.print(ansi.dim() + FRAMES.charAt(frame) + " " + elapsed / 1_000_000_000L + "s"
                + ansi.reset());
        out.flush();
    }
}
