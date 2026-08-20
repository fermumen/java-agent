package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decoder and lifecycle edges ported from fx's terminal contract tests. */
class TerminalToolEdgeParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void knownNullPlaceholdersAreElidedButUnknownNullFieldsAreRejected() throws Exception {
        Tool terminal = terminal();
        ObjectNode accepted = json.createObjectNode().put("action", "start");
        accepted.putNull("session_id").putNull("cursor_segment").putNull("write").putNull("signal");
        assertTrue(terminal.requiresApproval(accepted));

        ObjectNode rejected = json.createObjectNode().put("action", "start");
        rejected.putNull("unknown");
        assertThrows(IllegalArgumentException.class, () -> terminal.requiresApproval(rejected));
    }

    @Test
    void boundedPlainScreenAndMonitorLifecycleWorkWhileTmuxRemainsUnavailable() throws Exception {
        Tool terminal = terminal();
        JsonNode started = call(terminal, args("action", "start", "command",
                windows() ? "echo plain-screen" : "printf plain-screen"));
        String id = started.path("success").path("start").path("session").path("session_id").asText();
        call(terminal, args("action", "wait", "session_id", id,
                "return_when", json.createObjectNode().put("kind", "exit"), "wait_ceiling_ms", 5_000));
        JsonNode screen = call(terminal, args("action", "screen", "session_id", id));
        assertEquals("p", screen.path("success").path("screen").path("snapshot")
                .path("cells").path(0).path("text").asText());
        assertFalse(terminal.isErrorResult(screen.toString()));

        ObjectNode add = json.createObjectNode().put("kind", "add");
        add.set("definition", monitorDefinition("plain-screen", "until_match"));
        JsonNode added = call(terminal, args("action", "monitor", "session_id", id, "monitor", add));
        assertEquals("monitor-1", added.path("success").path("monitor").path("monitor_id").asText());
        JsonNode inspected = call(terminal, args("action", "inspect", "session_id", id));
        assertEquals("matched", inspected.path("success").path("inspect").path("events").path(0)
                .path("reason").asText());
        assertEquals(0, inspected.path("success").path("inspect").path("session")
                .path("active_monitor_count").asInt());

        String tmux = terminal.execute(args("action", "start", "backend", "tmux", "command", "echo no"));
        assertEquals("unsupported_host", json.readTree(tmux).path("failure").path("code").asText());

        call(terminal, args("action", "close", "session_id", id, "close_policy", "graceful"));
    }

    @Test
    void startCanReturnOnOutputMatchThenResizeSignalAndClose() throws Exception {
        Tool terminal = terminal();
        String command = windows() ? "echo ready & ping -n 6 127.0.0.1 >nul" : "printf ready; sleep 5";
        ObjectNode match = json.createObjectNode().put("kind", "match").put("pattern", "ready");
        JsonNode started = call(terminal, args("action", "start", "command", command,
                "return_when", match, "wait_ceiling_ms", 3_000));
        assertTrue(started.path("success").path("start").path("outcome").has("condition_met"));
        String id = started.path("success").path("start").path("session").path("session_id").asText();

        JsonNode resized = call(terminal, args("action", "resize", "session_id", id,
                "rows", 40, "columns", 120));
        assertEquals(40, resized.path("success").path("resize").path("dimensions").path("rows").asInt());
        JsonNode signaled = call(terminal, args("action", "signal", "session_id", id, "signal", "kill"));
        assertEquals("kill", signaled.path("success").path("signal").path("signal").asText());
        JsonNode closed = call(terminal, args("action", "close", "session_id", id, "close_policy", "force"));
        assertEquals("closed", closed.path("success").path("close").path("session").path("lifecycle").asText());
        assertFalse(terminal.isErrorResult(closed.toString()));
    }

    private ObjectNode monitorDefinition(String pattern, String lifetime) {
        ObjectNode definition = json.createObjectNode();
        definition.set("condition", json.createObjectNode().put("kind", "output_contains")
                .put("pattern", pattern));
        definition.set("notify", json.createObjectNode().put("kind", "on_match"));
        definition.set("lifetime", json.createObjectNode().put("kind", lifetime));
        return definition;
    }

    private Tool terminal() throws Exception {
        return WorkspaceTools.create(workspace).stream()
                .filter(tool -> tool.name().equals("terminal")).findFirst().orElseThrow();
    }

    private JsonNode call(Tool terminal, ObjectNode arguments) throws Exception {
        return json.readTree(terminal.execute(arguments));
    }

    private ObjectNode args(Object... values) {
        ObjectNode result = json.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            Object value = values[index + 1];
            if (value instanceof JsonNode) result.set((String) values[index], (JsonNode) value);
            else if (value instanceof Integer) result.put((String) values[index], (Integer) value);
            else result.put((String) values[index], String.valueOf(value));
        }
        return result;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
