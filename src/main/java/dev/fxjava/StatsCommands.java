package dev.fxjava;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Shared /stats command handling for the raw shell and the legacy fallback
 * loop: one line of cumulative totals for the active session, an all-time
 * line summed across every saved session on disk, and a bounded per-session
 * breakdown of the most recent saved sessions for this workspace. Output is
 * dim through {@link Ansi} on TTY paths and byte-identical plain text
 * otherwise; malformed arguments earn a usage line.
 */
final class StatsCommands {
    static final int RECENT_SESSIONS_SHOWN = 10;
    /** Upper bound on snapshots loaded for the all-time sum. */
    static final int ALL_TIME_SCAN_LIMIT = 200;
    static final String USAGE = "Usage: /stats";

    private StatsCommands() {
    }

    /** fx-shaped totals fragment, e.g. "1234 in · 567 out · 1801 total". */
    static String format(long inputTokens, long outputTokens) {
        return inputTokens + " in · " + outputTokens + " out · "
                + (inputTokens + outputTokens) + " total";
    }

    /** Compact per-turn form for the interactive shell: {@code ↑ 1.2k ↓ 340}. */
    static String compact(long inputTokens, long outputTokens) {
        return "↑ " + abbreviate(inputTokens) + " ↓ " + abbreviate(outputTokens);
    }

    static String abbreviate(long tokens) {
        if (tokens < 1_000) return Long.toString(tokens);
        if (tokens < 1_000_000) return scaled(tokens, 1_000) + "k";
        return scaled(tokens, 1_000_000) + "M";
    }

    /** One decimal below ten units ({@code 1.2k}), whole units above ({@code 48k}). */
    private static String scaled(long tokens, long unit) {
        if (tokens < 10 * unit) {
            long tenths = tokens * 10 / unit;
            return tenths % 10 == 0 ? Long.toString(tenths / 10) : tenths / 10 + "." + tenths % 10;
        }
        return Long.toString(tokens / unit);
    }

    /** Handles one /stats invocation; {@code argument} is the trimmed remainder. */
    static void handle(SessionRuntime session, String argument, java.nio.file.Path workspace,
                       Ansi ansi, PrintStream out) {
        String rest = argument == null ? "" : argument.strip();
        if (!rest.isEmpty()) {
            out.println(USAGE);
            return;
        }
        SessionUsage current = session.usage();
        String label = session.persistent() ? "session " + session.id()
                : "session (unsaved --no-save)";
        out.println(label + ": " + ansi.dim()
                + format(current.inputTokens(), current.outputTokens()) + ansi.reset());
        SessionRuntime.AllSessions scanned;
        try {
            scanned = session.allTimeSessions(ALL_TIME_SCAN_LIMIT);
        } catch (IOException unavailable) {
            out.println(ansi.dim() + "all-time totals unavailable: "
                    + unavailable.getMessage() + ansi.reset());
            return;
        }
        long allInput = 0;
        long allOutput = 0;
        for (SessionStore.Snapshot snapshot : scanned.sessions()) {
            allInput += snapshot.usage().inputTokens();
            allOutput += snapshot.usage().outputTokens();
        }
        // When the scan hit its cap, say so instead of implying completeness.
        String scope;
        if (scanned.totalFound() > scanned.sessions().size()) {
            scope = "the most recent " + scanned.sessions().size() + " of "
                    + scanned.totalFound() + " saved sessions";
        } else {
            scope = scanned.totalFound() + " saved session"
                    + (scanned.totalFound() == 1 ? "" : "s");
        }
        out.println("all-time across " + scope + ": " + ansi.dim()
                + format(allInput, allOutput) + ansi.reset());
        int shown = 0;
        boolean breakdownFailed = false;
        try {
            for (SessionStore.Snapshot saved : session.sessions(workspace, RECENT_SESSIONS_SHOWN)) {
                boolean active = saved.id().equals(session.id());
                out.println((active ? "  *" : "   ") + saved.id() + "  " + ansi.dim()
                        + format(saved.usage().inputTokens(), saved.usage().outputTokens())
                        + ansi.reset());
                shown++;
            }
        } catch (IOException unavailable) {
            breakdownFailed = true;
            out.println(ansi.dim() + "per-session breakdown unavailable (best effort): "
                    + unavailable.getMessage() + ansi.reset());
        }
        if (shown == 0 && !breakdownFailed) {
            out.println(ansi.dim() + "No per-session breakdown yet." + ansi.reset());
        }
    }
}
