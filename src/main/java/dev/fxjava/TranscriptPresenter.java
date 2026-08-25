package dev.fxjava;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Presents one agent turn on the raw terminal: braille spinner while nothing
 * new is showing, live tool-group lines rewritten in place, and markdown
 * rendered from complete lines only. All output funnels through one monitor so
 * spinner frames and content never interleave; the spinner is always fully
 * erased before any content prints.
 */
final class TranscriptPresenter {
    private final PrintStream out;
    private final Ansi ansi;
    private final int columns;
    private final MarkdownConsole markdown;
    private final Spinner spinner;
    private final Object lock = new Object();
    private final StringBuilder pendingLine = new StringBuilder();
    private final List<String> blockLines = new ArrayList<>();
    private boolean inFence;
    private boolean separateNext;
    private boolean closed;

    TranscriptPresenter(PrintStream out, Ansi ansi, int columns, Spinner spinner) {
        this.out = out;
        this.ansi = ansi;
        this.columns = columns;
        this.spinner = spinner;
        this.markdown = new MarkdownConsole(ansi);
    }

    /** Shows the idle indicator before the turn's first output. */
    void begin() {
        spinner.start();
    }

    /** Poll hook for the input loop; repaints only when the interval elapsed. */
    void tick() {
        spinner.tick();
    }

    /** Erases the idle indicator before an inline prompt takes over the line. */
    void suspendSpinner() {
        synchronized (lock) {
            if (!closed) spinner.stop();
        }
    }

    void onDelta(String delta) {
        synchronized (lock) {
            if (closed) return;
            spinner.stop();
            pendingLine.append(delta);
            int cut;
            while ((cut = pendingLine.indexOf("\n")) >= 0) {
                String line = pendingLine.substring(0, cut);
                pendingLine.delete(0, cut + 1);
                ingest(line);
            }
        }
    }

    void onToolStart(String name, String preview) {
        synchronized (lock) {
            if (closed) return;
            spinner.stop();
            out.print(ToolGroupLines.running(name, preview, columns, ansi));
            out.flush();
        }
    }

    /** Rewrites the running tool line in place and resumes the idle spinner. */
    void onToolEnd(String name, boolean error) {
        synchronized (lock) {
            if (closed) return;
            out.print('\r');
            out.print(ansi.eraseLine());
            out.print(ToolGroupLines.completed(name, "", error, columns, ansi));
            out.println();
            out.flush();
            separateNext = true;
            spinner.start();
        }
    }

    /**
     * Flushes any partial trailing line and block, then closes: later deltas
     * are dropped so a cancelled turn cannot print after its cleanup.
     */
    void finish() {
        synchronized (lock) {
            if (!closed && pendingLine.length() > 0) {
                ingest(pendingLine.toString());
                pendingLine.setLength(0);
            }
            inFence = false;
            flushBlock();
            spinner.stop();
            closed = true;
        }
    }

    /** Drops unflushed output and stops the spinner before a cancel message. */
    void cancel() {
        synchronized (lock) {
            closed = true;
            pendingLine.setLength(0);
            blockLines.clear();
            inFence = false;
            spinner.stop();
        }
    }

    private void ingest(String line) {
        if (!inFence && line.isBlank()) {
            flushBlock();
            return;
        }
        blockLines.add(line);
        if (line.strip().startsWith("```")) {
            if (inFence) {
                inFence = false;
                flushBlock();
            } else {
                inFence = true;
            }
        }
    }

    private void flushBlock() {
        if (blockLines.isEmpty()) return;
        String source = String.join("\n", blockLines);
        blockLines.clear();
        String rendered = markdown.render(source, columns);
        if (rendered.isEmpty()) return;
        if (separateNext) out.print('\n');
        out.print(rendered);
        out.println();
        out.flush();
        separateNext = true;
    }
}
