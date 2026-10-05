package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clock-driven spinner frames plus the exact erase bytes on stop. */
class SpinnerTest {
    private final AtomicLong now = new AtomicLong();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private Spinner newSpinner() {
        return new Spinner(new PrintStream(bytes, true, StandardCharsets.UTF_8), Ansi.of(true),
                now::get, 100_000_000L);
    }

    @Test
    void startPaintsTheFirstFrameImmediately() {
        Spinner spinner = newSpinner();
        spinner.start();
        assertEquals("\r\u001b[2K\u001b[38;5;243m⠋\u001b[0m "
                + Spinner.shimmer("Thinking…", 0, Ansi.of(true))
                + " \u001b[38;5;243m0s\u001b[0m", output());
    }

    @Test
    void plainTerminalsGetTheUnstyledLabel() {
        Spinner spinner = new Spinner(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                Ansi.of(false), now::get, 100_000_000L, "Compacting…");
        spinner.start();
        assertEquals("\r\u001b[2K⠋ Compacting… 0s", output());
    }

    @Test
    void shimmerHeadSweepsAcrossTheLabel() {
        Ansi ansi = Ansi.of(true);
        String bright = "\u001b[38;5;255m";
        // The head enters from the left: off-screen at step 0, on the first letter at step 4.
        assertFalse(Spinner.shimmer("abc", 0, ansi).contains(bright));
        assertTrue(Spinner.shimmer("abc", 4, ansi).startsWith(bright + "a"));
        assertTrue(Spinner.shimmer("abc", 6, ansi).contains(bright + "c"));
        assertEquals("abc", Spinner.shimmer("abc", 6, Ansi.of(false)));
    }

    @Test
    void durationsStayCompact() {
        assertEquals("0.4s", Spinner.shortDuration(400_000_000L));
        assertEquals("12s", Spinner.shortDuration(12_000_000_000L));
        assertEquals("2m 05s", Spinner.duration(125_000_000_000L));
    }

    @Test
    void tickRepaintsOnlyAfterTheIntervalAndAdvancesFrames() {
        Spinner spinner = newSpinner();
        spinner.start();
        reset();
        now.addAndGet(50_000_000L);
        spinner.tick();
        assertEquals("", output());
        now.addAndGet(50_000_000L);
        spinner.tick();
        assertTrue(output().startsWith("\r\u001b[2K\u001b[38;5;243m⠙"), output());
        reset();
        now.addAndGet(900_000_000L);
        spinner.tick();
        assertTrue(output().startsWith("\r\u001b[2K\u001b[38;5;243m⠋"), output());
        assertTrue(output().endsWith(" \u001b[38;5;243m1s\u001b[0m"), output());
    }

    @Test
    void stopEmitsEraseBytesOnceAndSilencesLaterTicks() {
        Spinner spinner = newSpinner();
        spinner.start();
        spinner.stop();
        String beforeStop = output();
        reset();
        spinner.stop();
        spinner.tick();
        now.addAndGet(1_000_000_000L);
        spinner.tick();
        assertFalse(spinner.active());
        assertEquals("", output());
        assertTrue(beforeStop.endsWith("0s\u001b[0m\r\u001b[2K"), beforeStop);
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void reset() {
        bytes.reset();
    }
}
