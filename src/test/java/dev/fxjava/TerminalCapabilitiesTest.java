package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Capability detection branches: TTY, TERM, NO_COLOR, and size sources. */
class TerminalCapabilitiesTest {
    @Test
    void interactiveRequiresConsoleAndRealTerm() {
        assertTrue(TerminalCapabilities.detect(Map.of("TERM", "xterm-256color"), true).interactive());
        assertFalse(TerminalCapabilities.detect(Map.of("TERM", "xterm-256color"), false).interactive());
        assertFalse(TerminalCapabilities.detect(Map.of("TERM", "dumb"), true).interactive());
        assertTrue(TerminalCapabilities.detect(Collections.emptyMap(), true).interactive(),
                "only TERM=dumb suppresses the terminal, per spec");
    }

    @Test
    void noColorDisablesColorButKeepsInteractive() {
        Map<String, String> plain = new HashMap<>();
        plain.put("TERM", "xterm");
        TerminalCapabilities capabilities = TerminalCapabilities.detect(plain, true);
        assertTrue(capabilities.interactive());
        assertTrue(capabilities.color());

        plain.put("NO_COLOR", "1");
        assertFalse(TerminalCapabilities.detect(plain, true).color());

        plain.put("NO_COLOR", "");
        assertTrue(TerminalCapabilities.detect(plain, true).color(),
                "empty NO_COLOR is treated as unset");
    }

    @Test
    void nonTerminalsNeverGetColor() {
        assertFalse(TerminalCapabilities.detect(Map.of("TERM", "xterm"), false).color());
    }

    @Test
    void parsesSttySizeOutput() {
        assertEquals(new TerminalCapabilities.Size(30, 100), TerminalCapabilities.parse("30 100"));
        assertEquals(new TerminalCapabilities.Size(24, 80), TerminalCapabilities.parse("  24 80\n"));
        assertEquals(TerminalCapabilities.Size.UNKNOWN, TerminalCapabilities.parse(""));
        assertEquals(TerminalCapabilities.Size.UNKNOWN, TerminalCapabilities.parse("80"));
        assertEquals(TerminalCapabilities.Size.UNKNOWN, TerminalCapabilities.parse("rows cols"));
        assertEquals(TerminalCapabilities.Size.UNKNOWN, TerminalCapabilities.parse("-5 80"));
        assertEquals(TerminalCapabilities.Size.UNKNOWN, TerminalCapabilities.parse(null));
    }

    @Test
    void unavailableSizeSourceFallsBackWithoutThrowing() {
        assertFalse(TerminalCapabilities.Size.UNKNOWN.known());
        assertTrue(TerminalCapabilities.Size.FALLBACK.known());
        TerminalCapabilities.SizeSource unavailable = TerminalCapabilities.SizeSource.unavailable();
        assertEquals(TerminalCapabilities.Size.UNKNOWN, unavailable.query());

        Map<String, String> environment = new HashMap<>();
        environment.put("TERM", "xterm");
        TerminalCapabilities piped = TerminalCapabilities.detect(environment, false,
                TerminalCapabilities.SizeSource.unavailable());
        assertFalse(piped.interactive());
        assertFalse(piped.size().known());
    }

    @Test
    void sttySizeSourceNeverThrowsOutsideTerminal() {
        if (System.console() != null) return;
        TerminalCapabilities.Size size = new TerminalCapabilities.SttySizeSource().query();
        assertEquals(TerminalCapabilities.Size.UNKNOWN, size,
                "no controlling TTY in CI means unknown size");
    }

    @Test
    void injectedSizeSourceDrivesReportedSize() {
        class Fixed implements TerminalCapabilities.SizeSource {
            TerminalCapabilities.Size value = new TerminalCapabilities.Size(42, 111);

            @Override
            public TerminalCapabilities.Size query() {
                return value;
            }
        }
        Fixed fixed = new Fixed();
        Map<String, String> environment = new HashMap<>();
        environment.put("TERM", "xterm");
        TerminalCapabilities capabilities = TerminalCapabilities.detect(environment, true, fixed);
        assertTrue(capabilities.interactive());
        assertEquals(new TerminalCapabilities.Size(42, 111), capabilities.size());
        fixed.value = new TerminalCapabilities.Size(10, 20);
        assertEquals(new TerminalCapabilities.Size(10, 20), capabilities.size(),
                "size queries re-poll the source");
    }
}
