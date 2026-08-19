package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultStoreParityTest {
    private static final Pattern HANDLE = Pattern.compile("<tool_result_handle>([^<]+)</tool_result_handle>");
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void largeResultGetsStableBoundedPreviewRangeAndQuery() throws Exception {
        ToolResultStore store = new ToolResultStore(root);
        store.setSession("session-1");
        String full = "search preview\nneedle from full search result\n" + "é".repeat(9_000);
        String prepared = store.prepare("call-search", "web_search", full);
        assertTrue(prepared.contains("<tool_result_preview"));
        Matcher match = HANDLE.matcher(prepared);
        assertTrue(match.find());
        String handle = match.group(1);
        assertEquals(prepared, store.prepare("call-search", "web_search", full));

        Tool reader = new ReadToolResultTool(store);
        ObjectNode query = json.createObjectNode().put("handle", handle).put("query", "needle");
        assertTrue(reader.execute(query).contains("2|needle from full search result"));
        ObjectNode range = json.createObjectNode().put("handle", handle).put("start_byte", 1)
                .put("byte_count", 20);
        String page = reader.execute(range);
        assertTrue(page.contains("search preview"));
        assertTrue(page.contains("total_bytes="));
    }

    @Test
    void handleIsSessionScopedAndRejectsTraversal() throws Exception {
        ToolResultStore store = new ToolResultStore(root);
        store.setSession("session-a");
        String prepared = store.prepare("call", "tool", "x".repeat(20_000));
        Matcher match = HANDLE.matcher(prepared);
        assertTrue(match.find());
        String handle = match.group(1);
        store.setSession("session-b");
        Tool reader = new ReadToolResultTool(store);
        assertThrows(Exception.class, () -> reader.execute(json.createObjectNode().put("handle", handle)));
        assertThrows(Exception.class, () -> reader.execute(json.createObjectNode().put("handle", "../" + handle)));
    }

    @Test
    void smallResultsStayInlineAndEphemeralModeStillSupportsHandles() throws Exception {
        ToolResultStore store = new ToolResultStore(root);
        assertEquals("small", store.prepare("call", "tool", "small"));
        String prepared = store.prepare("call", "tool", "x".repeat(20_000));
        Matcher match = HANDLE.matcher(prepared);
        assertTrue(match.find());
        assertTrue(store.read(match.group(1), 1, 32, null).contains("xxxxxxxx"));
    }
}
