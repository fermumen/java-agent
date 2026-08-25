package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertEquals("\r\u001b[2K\u001b[2m⠋ 0s\u001b[0m", output());
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
        assertEquals("\r\u001b[2K\u001b[2m⠙ 0s\u001b[0m", output());
        reset();
        now.addAndGet(900_000_000L);
        spinner.tick();
        assertEquals("\r\u001b[2K\u001b[2m⠋ 1s\u001b[0m", output());
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
        assertEquals("\r\u001b[2K\u001b[2m⠋ 0s\u001b[0m" + "\r\u001b[2K", beforeStop);
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void reset() {
        bytes.reset();
    }
}
