package dev.fxjava;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Raw-mode terminal control: {@code stty} on Unix-like systems, the console
 * API ({@link WindowsConsole}) on Windows. Saves the exact prior state before
 * entering raw mode, restores exactly that state on close, and keeps a JVM
 * shutdown hook as a safety net. Reports unsupported cleanly so the required
 * raw UI can explain why it could not start.
 */
final class RawTerminal implements AutoCloseable {
    /** Puts the terminal back exactly as it was found. */
    interface Restore {
        void run() throws InterruptedException;
    }

    private final Restore restorer;
    private final TerminalCapabilities.SizeSource sizeSource;
    private final InputStream input;
    private final Thread shutdownHook;
    private boolean closed;

    private RawTerminal(Restore restore, TerminalCapabilities.SizeSource sizeSource, InputStream input) {
        this.restorer = restore;
        this.sizeSource = sizeSource;
        this.input = input;
        this.shutdownHook = new Thread(this::restoreQuietly, "raw-terminal-restore");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /** A raw session whose keys arrive on {@code input} instead of standard input. */
    static RawTerminal of(Restore restore, TerminalCapabilities.SizeSource sizeSource, InputStream input) {
        return new RawTerminal(restore, sizeSource, input);
    }

    /**
     * Enters raw mode on the controlling terminal, or returns null after
     * telling {@code declined} why, so startup failures are never silent.
     */
    static RawTerminal open(Consumer<String> declined) throws InterruptedException {
        if (System.console() == null) {
            declined.accept("Java sees no console (input or output is redirected)");
            return null;
        }
        if (WindowsConsole.isWindows()) return WindowsConsole.open(declined);
        return open(new SttyRunner(), new TerminalCapabilities.SttySizeSource(), declined);
    }

    static RawTerminal open(Stty stty, TerminalCapabilities.SizeSource sizeSource) throws InterruptedException {
        return open(stty, sizeSource, reason -> { });
    }

    static RawTerminal open(Stty stty, TerminalCapabilities.SizeSource sizeSource, Consumer<String> declined)
            throws InterruptedException {
        String saved = stty.runCapture("stty", "-g");
        if (saved == null || saved.isBlank()) {
            declined.accept("stty -g could not read the terminal state");
            return null;
        }
        if (!stty.runInherit("stty", "raw", "-echo")) {
            declined.accept("stty raw -echo failed");
            return null;
        }
        String state = saved.trim();
        return new RawTerminal(() -> stty.runInherit("stty", state), sizeSource, null);
    }

    /** Where keys arrive: the backend's own stream, or {@code standardInput}. */
    InputStream input(InputStream standardInput) {
        return input == null ? standardInput : input;
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
            restorer.run();
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
