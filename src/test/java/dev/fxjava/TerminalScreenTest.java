package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerminalScreenTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rendersExactRowMajorGridCursorAndDefaultModes() {
        var snapshot = TerminalScreen.render(json, "ab\ncd", 2, 4);
        assertEquals(8, snapshot.path("cells").size());
        assertEquals("a", snapshot.path("cells").path(0).path("text").asText());
        assertEquals("b", snapshot.path("cells").path(1).path("text").asText());
        assertEquals("c", snapshot.path("cells").path(4).path("text").asText());
        assertEquals("d", snapshot.path("cells").path(5).path("text").asText());
        assertEquals("blank", snapshot.path("cells").path(7).path("kind").asText());
        assertEquals(1, snapshot.path("cursor").path("row").asInt());
        assertEquals(2, snapshot.path("cursor").path("column").asInt());
        assertEquals(true, snapshot.path("modes").path("autowrap").asBoolean());
    }

    @Test
    void appliesBackspaceTabsWrappingAndScroll() {
        var snapshot = TerminalScreen.render(json, "12\b3\nA\tB\nlast", 2, 9);
        assertEquals("l", snapshot.path("cells").path(9).path("text").asText());
        assertEquals("a", snapshot.path("cells").path(10).path("text").asText());
        assertEquals("s", snapshot.path("cells").path(11).path("text").asText());
        assertEquals("t", snapshot.path("cells").path(12).path("text").asText());
    }

    @Test
    void refusesStateItCannotFaithfullyRender() {
        assertNull(TerminalScreen.render(json, "\u001b[31mred", 24, 80));
        assertNull(TerminalScreen.render(json, "界", 24, 80));
        assertNull(TerminalScreen.render(json, "plain", 500, 500));
    }
}
