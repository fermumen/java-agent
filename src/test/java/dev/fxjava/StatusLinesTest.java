package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Status hint composition and the throttled size-probe gate. */
class StatusLinesTest {
    @Test
    void hintJoinsModelModeAndShortSessionId() {
        String hint = StatusLines.hint("gpt-5.6", "ask", "20260825T1010-abcd1234ef56", 80, Ansi.of(false));
        assertEquals("gpt-5.6 · ask · 20260825", hint);
    }

    @Test
    void unsavedSessionsGetAPlaceholder() {
        assertEquals("m · auto · unsaved",
                StatusLines.hint("m", "auto", null, 80, Ansi.of(false)));
        assertEquals("m · ask · unsaved",
                StatusLines.hint("m", "ask", "  ", 80, Ansi.of(false)));
    }

    @Test
    void longHintsTruncateToTheTerminalWidth() {
        String hint = StatusLines.hint("a-very-long-model-identifier", "ask", "session", 10, Ansi.of(false));
        assertTrue(MarkdownConsole.visibleWidth(hint) <= 10);
        assertTrue(hint.startsWith("a-very-l"));
    }

    @Test
    void hintWrapsInDimWhenColorEnabled() {
        String hint = StatusLines.hint("model", "ask", "s1", 80, Ansi.of(true));
        assertEquals("\u001b[2mmodel · ask · s1\u001b[0m", hint);
    }

    @Test
    void refreshGateThrottlesProbesUntilForcedOrIntervalElapses() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        RefreshGate gate = new RefreshGate(now::get, 250_000_000L);
        assertTrue(gate.due(false), "first probe always runs");
        now.addAndGet(100_000_000L);
        assertFalse(gate.due(false), "inside the interval the probe is skipped");
        assertTrue(gate.due(true), "Ctrl+L forces an immediate probe");
        assertFalse(gate.due(false), "the forced probe restarts the interval window");
        now.addAndGet(250_000_001L);
        assertTrue(gate.due(false), "the window elapses relative to the forced probe");
    }
}
