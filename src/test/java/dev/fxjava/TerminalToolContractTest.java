package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public terminal cases ported from fx/src/tools/terminal/terminal.zig. */
class TerminalToolContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void advertisesEveryFxActionAndRejectsCrossActionFields() throws Exception {
        Tool terminal = terminal();
        JsonNode actions = terminal.parameters().path("properties").path("action").path("enum");
        assertEquals(List.of("exec", "start", "read", "screen", "write", "wait", "monitor",
                        "inspect", "list", "resize", "signal", "close"),
                json.convertValue(actions, json.getTypeFactory().constructCollectionType(List.class, String.class)));

        assertThrows(IllegalArgumentException.class, () -> terminal.execute(args("action", "start",
                "session_id", "terminal-a")));
        assertThrows(IllegalArgumentException.class, () -> terminal.execute(args("action", "list",
                "cwd", ".")));
        assertThrows(IllegalArgumentException.class, () -> terminal.execute(args("action", "resize",
                "command", "wrong")));
        assertThrows(IllegalArgumentException.class, () -> terminal.execute(args("action", "start",
                "profile", "user", "shell", json.createObjectNode().put("kind", "user_login"))));
    }

    @Test
    void startWaitReadInspectListAndCloseFollowStructuredLifecycle() throws Exception {
        Tool terminal = terminal();
        String command = windows() ? "echo durable-terminal" : "printf durable-terminal";
        JsonNode started = call(terminal, args("action", "start", "command", command));
        String id = started.path("success").path("start").path("session").path("session_id").asText();
        assertTrue(id.startsWith("terminal-"));
        assertEquals("running", started.path("success").path("start").path("session")
                .path("lifecycle").asText());

        ObjectNode returnWhen = json.createObjectNode().put("kind", "exit");
        JsonNode waited = call(terminal, args("action", "wait", "session_id", id,
                "return_when", returnWhen, "wait_ceiling_ms", 5_000));
        assertEquals(0, waited.path("success").path("wait").path("outcome").path("exited").asInt());

        JsonNode read = call(terminal, args("action", "read", "session_id", id,
                "cursor_segment", 1, "cursor_offset", 0));
        assertTrue(read.path("success").path("read").path("output").asText().contains("durable-terminal"));
        assertEquals("exited", read.path("success").path("read").path("session")
                .path("lifecycle").asText());

        JsonNode inspected = call(terminal, args("action", "inspect", "session_id", id));
        assertEquals(command, inspected.path("success").path("inspect").path("command").asText());
        JsonNode listed = call(terminal, args("action", "list"));
        assertEquals(id, listed.path("success").path("list").path("sessions").path(0)
                .path("session_id").asText());

        JsonNode closed = call(terminal, args("action", "close", "session_id", id,
                "close_policy", "graceful"));
        assertEquals("closed", closed.path("success").path("close").path("session")
                .path("lifecycle").asText());
        assertEquals(0, call(terminal, args("action", "list")).path("success").path("list")
                .path("sessions").size());
    }

    @Test
    void writeFeedsAStartedProcessAndReadUsesByteCursor() throws Exception {
        Tool terminal = terminal();
        String command = windows() ? "set /p line= & call echo got:%%line%%" : "read line; printf 'got:%s' \"$line\"";
        JsonNode started = call(terminal, args("action", "start", "command", command));
        String id = started.path("success").path("start").path("session").path("session_id").asText();

        ObjectNode payload = json.createObjectNode().put("kind", "text")
                .put("text", windows() ? "hello\r\n" : "hello\n");
        JsonNode written = call(terminal, args("action", "write", "session_id", id, "write", payload));
        assertEquals(windows() ? 7 : 6, written.path("success").path("write")
                .path("accepted_bytes").asInt());
        call(terminal, args("action", "wait", "session_id", id,
                "return_when", json.createObjectNode().put("kind", "exit"), "wait_ceiling_ms", 5_000));

        JsonNode first = call(terminal, args("action", "read", "session_id", id,
                "cursor_segment", 1, "cursor_offset", 0));
        String output = first.path("success").path("read").path("output").asText();
        assertTrue(output.contains("got:hello"));
        long end = first.path("success").path("read").path("raw_range").path("end").path("offset").asLong();
        JsonNode second = call(terminal, args("action", "read", "session_id", id,
                "cursor_segment", 1, "cursor_offset", end));
        assertEquals("", second.path("success").path("read").path("output").asText());
    }

    @Test
    void readOnlyActionsDoNotRequireApprovalButMutationsDo() throws Exception {
        Tool terminal = terminal();
        assertFalse(terminal.requiresApproval(args("action", "list")));
        assertFalse(terminal.requiresApproval(args("action", "inspect", "session_id", "terminal-1")));
        assertTrue(terminal.requiresApproval(args("action", "exec", "command", "echo hi")));
        assertTrue(terminal.requiresApproval(args("action", "start")));
        assertTrue(terminal.requiresApproval(args("action", "write", "session_id", "terminal-1")));
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
        for (int i = 0; i < values.length; i += 2) {
            Object value = values[i + 1];
            if (value instanceof JsonNode node) result.set((String) values[i], node);
            else if (value instanceof Integer number) result.put((String) values[i], number);
            else if (value instanceof Long number) result.put((String) values[i], number);
            else result.put((String) values[i], String.valueOf(value));
        }
        return result;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
