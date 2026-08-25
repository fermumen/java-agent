package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streaming behavior: complete-line assembly, tool groups, cancel, trailing newline. */
class TranscriptPresenterTest {
    private final AtomicLong now = new AtomicLong();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private TranscriptPresenter newPresenter(int columns) {
        Spinner spinner = new Spinner(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                Ansi.of(false), now::get, 100_000_000L);
        return new TranscriptPresenter(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                Ansi.of(false), columns, spinner);
    }

    @Test
    void deltasHoldUntilCompleteLinesFormAndFinishFlushesThePartialLine() {
        TranscriptPresenter presenter = newPresenter(80);
        presenter.onDelta("Hello wor");
        presenter.onDelta("ld\nsecond ");
        presenter.onDelta("line");
        assertEquals("", output());
        presenter.finish();
        assertEquals("Hello world second line\n", output());
    }

    @Test
    void blankLinesFlushBlocksImmediately() {
        TranscriptPresenter presenter = newPresenter(80);
        presenter.onDelta("# Title\n\nbody ");
        presenter.finish();
        assertEquals("Title\n\nbody\n", output());
    }

    @Test
    void fencedCodeWaitsForTheClosingFence() {
        TranscriptPresenter presenter = newPresenter(80);
        presenter.onDelta("```java\nint x;\n```");
        assertTrue(output().isEmpty());
        presenter.onDelta("\nafter\n");
        presenter.finish();
        String rendered = output();
        assertTrue(rendered.contains("─".repeat(75) + " java"), rendered);
        assertTrue(rendered.contains("int x;"));
        assertTrue(rendered.endsWith("after\n"));
    }

    @Test
    void toolGroupsReplaceTheSpinnerAndRewriteInPlace() {
        TranscriptPresenter presenter = newPresenter(80);
        presenter.begin();
        presenter.onToolStart("write_file", "write smoke.txt");
        presenter.onToolEnd("write_file", false);
        presenter.onDelta("# Done\n");
        presenter.finish();
        assertEquals("\r\u001b[2K⠋ 0s"
                + "\r\u001b[2K● write_file write smoke.txt"
                + "\r\u001b[2K✓ write_file\n"
                + "\r\u001b[2K⠋ 0s"
                + "\r\u001b[2K\nDone\n", output());
    }

    @Test
    void cancelDropsPendingOutputAndIgnoresLateDeltas() {
        TranscriptPresenter presenter = newPresenter(80);
        presenter.begin();
        presenter.cancel();
        assertEquals("\r\u001b[2K⠋ 0s\r\u001b[2K", output());
        presenter.onDelta("late partial ");
        presenter.onToolStart("echo", "hi");
        presenter.finish();
        assertEquals("\r\u001b[2K⠋ 0s\r\u001b[2K", output());
    }

    @Test
    void finishedTurnEmitsExactlyOneTrailingNewline() {
        TranscriptPresenter presenter = newPresenter(80);
        presenter.begin();
        presenter.onDelta("answer text\n");
        presenter.finish();
        String rendered = output();
        assertTrue(rendered.endsWith("answer text\n"));
        assertTrue(!rendered.endsWith("\n\n"));
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
