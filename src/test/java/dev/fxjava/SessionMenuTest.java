package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMenuTest {
    private final ObjectMapper json = new ObjectMapper();

    private SessionStore.Snapshot saved(String id, String title, ArrayNode input) {
        return new SessionStore.Snapshot(id, "workspace", "model", title, "instructions", 0, 0, input);
    }

    @Test
    void previewUsesFirstUserMessageNotInstructionsOrTheLastAnswer() {
        ArrayNode input = json.createArrayNode();
        input.addObject().put("role", "system").put("content", "hidden instructions");
        input.addObject().put("role", "user").put("content", "first question\nwith details");
        input.addObject().put("role", "assistant").put("content", "answer");
        input.addObject().put("role", "user").put("content", "later question");
        assertEquals("first question with details", SessionMenu.preview(saved("one", "", input)));
    }

    @Test
    void imageMessagesUseTextAndPreviewCannotInjectTerminalControlsOrSecrets() {
        ArrayNode input = json.createArrayNode();
        ArrayNode content = input.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "input_image").put("image_url", "data:image/png;base64,hidden");
        content.addObject().put("type", "input_text").put("text", "Inspect image\u001b[2J api_key=private-token");
        String preview = SessionMenu.preview(saved("one", "", input));
        assertTrue(preview.contains("Inspect image"));
        assertFalse(preview.contains("\u001b"));
        assertFalse(preview.contains("private-token"));
        assertFalse(preview.contains("data:image"));
    }

    @Test
    void emptyConversationsHaveAnExplicitPreview() {
        assertEquals("(empty conversation)", SessionMenu.preview(saved("one", "", json.createArrayNode())));
    }

    @Test
    void selectionScrollsAndWrapsWithoutDroppingOlderEntries() {
        SessionMenu menu = new SessionMenu();
        List<SessionStore.Snapshot> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) entries.add(saved("id-" + i, "Title " + i, json.createArrayNode()));
        menu.open(entries);
        assertEquals("id-0", menu.selected().id());
        menu.move(-1);
        assertEquals("id-11", menu.selected().id());
        List<String> rows = menu.rows(50, 3, "id-11", Ansi.of(false));
        assertEquals(4, rows.size(), "heading plus the visible rows");
        assertTrue(rows.get(3).contains("› * id-11 Title 11"));
        assertFalse(String.join("\n", rows).contains("id-0 "));
        for (String row : rows) assertTrue(MarkdownConsole.visibleWidth(row) < 50, row);
        menu.move(1);
        assertEquals("id-0", menu.selected().id());
        menu.close();
        assertFalse(menu.active());
        assertNull(menu.selected());
    }
}
