package dev.fxjava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Raw-mode terminal control via {@code stty}. Saves the exact {@code stty -g}
 * state before entering raw mode, restores exactly that state on close, and
 * keeps a JVM shutdown hook as a safety net. Reports unsupported cleanly when
 * stty is missing or fails so callers can fall back to line input.
 */
final class RawTerminal implements AutoCloseable {
    private final Stty stty;
    private final String savedState;
    private final TerminalCapabilities.SizeSource sizeSource;
    private final Thread shutdownHook;
    private boolean closed;

    private RawTerminal(Stty stty, String savedState, TerminalCapabilities.SizeSource sizeSource) {
        this.stty = stty;
        this.savedState = savedState;
        this.sizeSource = sizeSource;
        this.shutdownHook = new Thread(this::restoreQuietly, "raw-terminal-restore");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /** Enters raw mode on the controlling terminal, or returns null when unsupported. */
    static RawTerminal open() throws InterruptedException {
        if (System.console() == null) return null;
        return open(new SttyRunner(), new TerminalCapabilities.SttySizeSource());
    }

    static RawTerminal open(Stty stty, TerminalCapabilities.SizeSource sizeSource) throws InterruptedException {
        String saved = stty.runCapture("stty", "-g");
        if (saved == null || saved.isBlank()) return null;
        if (!stty.runInherit("stty", "raw", "-echo")) return null;
        return new RawTerminal(stty, saved.trim(), sizeSource);
    }

    TerminalCapabilities.Size size() {
        return sizeSource.query();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // JVM is exiting through the hook itself; nothing to deregister.
        }
        restore();
    }

    /** The safety-net action registered with the JVM; also invoked by close(). */
    synchronized void restoreQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // Best-effort during shutdown.
        }
    }

    private void restore() {
        try {
            stty.runInherit("stty", savedState);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs an stty command against the controlling terminal; returns its stdout when capturing. */
    interface Stty {
        String runCapture(String... command) throws InterruptedException;

        boolean runInherit(String... command) throws InterruptedException;
    }

    private static final class SttyRunner implements Stty {
        @Override
        public String runCapture(String... command) throws InterruptedException {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
                return process.exitValue() == 0
                        ? new String(output, StandardCharsets.UTF_8).trim() : null;
            } catch (IOException unsupported) {
                return null;
            }
        }

        @Override
        public boolean runInherit(String... command) throws InterruptedException {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException unsupported) {
                return false;
            }
        }
    }
}
