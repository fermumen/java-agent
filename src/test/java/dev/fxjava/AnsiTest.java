package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ansi emits color only when enabled; layout control sequences always render. */
class AnsiTest {
    @Test
    void colorsRenderWhenEnabled() {
        Ansi ansi = Ansi.of(true);
        assertEquals("\u001b[36m", ansi.fg(Ansi.CYAN));
        assertEquals("\u001b[41m", ansi.bg(Ansi.RED));
        assertEquals("\u001b[1m", ansi.bold());
        assertEquals("\u001b[2m", ansi.dim());
        assertEquals("\u001b[0m", ansi.reset());
    }

    @Test
    void colorsNoOpWhenDisabled() {
        Ansi ansi = Ansi.of(false);
        assertEquals("", ansi.fg(Ansi.CYAN));
        assertEquals("", ansi.bg(Ansi.RED));
        assertEquals("", ansi.bold());
        assertEquals("", ansi.dim());
        assertEquals("", ansi.reset());
    }

    @Test
    void noColorEnvironmentDisablesColorsOnly() {
        Ansi noColor = Ansi.fromEnvironment(Map.of("NO_COLOR", "1"), true);
        assertTrue(noColor.dim().isEmpty());
        assertEquals("› ", noColor.dim() + "› " + noColor.reset(),
                "layout survives with color disabled");
        Ansi color = Ansi.fromEnvironment(Map.of(), true);
        assertTrue(!color.reset().isEmpty());
        assertEquals("\u001b[2m› \u001b[0m", color.dim() + "› " + color.reset());
    }
}
