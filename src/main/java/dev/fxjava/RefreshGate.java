package dev.fxjava;

/**
 * Throttle for terminal-size probes: shells out at most once per interval
 * unless forced, driven by an injectable clock so tests stay deterministic.
 */
final class RefreshGate {
    private final Spinner.Clock clock;
    private final long intervalNanos;
    private long lastAt = Long.MIN_VALUE;

    RefreshGate(Spinner.Clock clock, long intervalNanos) {
        this.clock = clock;
        this.intervalNanos = intervalNanos;
    }

    /** Returns true when a refresh should run and records the attempt. */
    boolean due(boolean force) {
        long now = clock.nanoTime();
        if (!force && lastAt != Long.MIN_VALUE && now - lastAt < intervalNanos) return false;
        lastAt = now;
        return true;
    }
}
