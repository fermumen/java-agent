package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw-mode lifecycle: save before enter, exact restore on close, failure fallbacks. */
class RawTerminalTest {
    /** Records every stty invocation; returns canned values like the real tool. */
    private static final class RecordingStty implements RawTerminal.Stty {
        final List<String> commands = new ArrayList<>();
        String savedState = "g0001:isig";
        boolean captureSucceeds = true;
        boolean enterSucceeds = true;
        int captureCalls;
        int enterCalls;

        @Override
        public String runCapture(String... command) {
            commands.add(String.join(" ", command));
            if (!command[1].equals("-g")) throw new IllegalArgumentException(command[1]);
            captureCalls++;
            return captureSucceeds ? savedState : null;
        }

        @Override
        public boolean runInherit(String... command) {
            commands.add(String.join(" ", command));
            if (command[1].equals("raw")) enterCalls++;
            return enterSucceeds;
        }
    }

    private static TerminalCapabilities.SizeSource fixedSize(int rows, int columns) {
        return () -> new TerminalCapabilities.Size(rows, columns);
    }

    @Test
    void openSavesStateBeforeEnteringRawMode() throws InterruptedException {
        RecordingStty stty = new RecordingStty();
        RawTerminal terminal = RawTerminal.open(stty, fixedSize(30, 100));
        assertNotNull(terminal);
        assertEquals(2, stty.commands.size());
        assertEquals("stty -g", stty.commands.get(0), "state must be captured first");
        assertEquals("stty raw -echo", stty.commands.get(1));

        assertEquals(new TerminalCapabilities.Size(30, 100), terminal.size());
    }

    @Test
    void closeRestoresExactlyTheSavedState() throws InterruptedException, IOException {
        RecordingStty stty = new RecordingStty();
        stty.savedState = "4b00:23:bd:1f";
        RawTerminal terminal = RawTerminal.open(stty, fixedSize(24, 80));
        assertNotNull(terminal);
        try (RawTerminal ignored = terminal) {
            // simulate a session
        }
        assertEquals(3, stty.commands.size());
        assertEquals("stty 4b00:23:bd:1f", stty.commands.get(2),
                "restore must replay the exact saved string");
    }

    @Test
    void closeIsIdempotent() throws InterruptedException, IOException {
        RecordingStty stty = new RecordingStty();
        RawTerminal terminal = RawTerminal.open(stty, fixedSize(24, 80));
        assertNotNull(terminal);
        terminal.close();
        assertEquals(3, stty.commands.size(), "one save, one enter, one restore");
        terminal.close();
        assertEquals(3, stty.commands.size(),
                "second close performs no extra stty calls");
    }

    @Test
    void missingSttyReportsUnsupported() throws InterruptedException {
        RecordingStty stty = new RecordingStty();
        stty.captureSucceeds = false;
        assertNull(RawTerminal.open(stty, fixedSize(24, 80)));
        assertEquals(1, stty.captureCalls);
        assertEquals(0, stty.enterCalls, "raw mode is never attempted without saved state");
    }

    @Test
    void failedRawEntryRestoresAndReportsUnsupported() throws InterruptedException {
        RecordingStty stty = new RecordingStty();
        stty.enterSucceeds = false;
        assertNull(RawTerminal.open(stty, fixedSize(24, 80)));
        assertEquals(1, stty.enterCalls);
        assertTrue(stty.commands.get(0).endsWith("-g"));
    }

    @Test
    void safetyNetRestoresWhenCloseWasNeverCalled() throws InterruptedException {
        RecordingStty stty = new RecordingStty();
        stty.savedState = "saved:state";
        RawTerminal terminal = RawTerminal.open(stty, fixedSize(24, 80));
        assertNotNull(terminal);
        // Same action the JVM shutdown hook wraps.
        terminal.restoreQuietly();
        assertTrue(stty.commands.contains("stty saved:state"),
                "safety net replays the exact saved state");
        terminal.close();
        assertEquals(3, stty.commands.size(), "close after the safety net adds nothing");
    }
}
