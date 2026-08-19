package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMonitorRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void initialOutputAndExitMonitorsCaptureSplitOutputExactlyOnce() throws Exception {
        Tool terminal = terminal();
        ArrayNode definitions = json.createArrayNode()
                .add(definition(condition("output_contains").put("pattern", "monitor-ready"), "until_session_end"))
                .add(definition(condition("process_exit"), "until_session_end"))
                .add(definition(condition("exit_code").put("exit_code", 17), "until_session_end"))
                .add(definition(condition("output_matches").put("pattern", "monitor-*ready"), "until_session_end"));
        String command = windows() ? "set /p \"=monitor-\" <nul & set /p \"=ready\" <nul & exit /b 17"
                : "printf monitor-; sleep 0.05; printf ready; exit 17";
        JsonNode started = call(terminal, args("action", "start", "command", command,
                "initial_monitors", definitions, "return_when", json.createObjectNode().put("kind", "exit"),
                "wait_ceiling_ms", 5_000));
        String id = started.path("success").path("start").path("session").path("session_id").asText();
        assertEquals(17, started.path("success").path("start").path("outcome").path("exited").asInt());

        JsonNode inspected = call(terminal, args("action", "inspect", "session_id", id));
        JsonNode body = inspected.path("success").path("inspect");
        assertEquals(0, body.path("session").path("active_monitor_count").asInt());
        assertEquals(4, body.path("events").size());
        Set<String> ids = new HashSet<>();
        body.path("events").forEach(event -> {
            ids.add(event.path("monitor_id").asText());
            assertEquals("matched", event.path("reason").asText());
        });
        assertEquals(Set.of("monitor-1", "monitor-2", "monitor-3", "monitor-4"), ids);

        long last = body.path("events").path(3).path("event_id").asLong();
        JsonNode acknowledged = call(terminal, args("action", "inspect", "session_id", id,
                "acknowledge_event_id", last));
        assertEquals(0, acknowledged.path("success").path("inspect").path("events").size());
        call(terminal, args("action", "close", "session_id", id, "close_policy", "graceful"));
    }

    @Test
    void addPauseResumeUpdateRemoveAndWorkspacePathBoundaryAreEnforced() throws Exception {
        Tool terminal = terminal();
        String command = windows() ? "ping -n 10 127.0.0.1 >nul" : "sleep 10";
        JsonNode started = call(terminal, args("action", "start", "command", command));
        String id = started.path("success").path("start").path("session").path("session_id").asText();

        ObjectNode add = operation("add");
        add.set("definition", stateDefinition("never"));
        assertEquals("monitor-1", call(terminal, args("action", "monitor", "session_id", id,
                "monitor", add)).path("success").path("monitor").path("monitor_id").asText());
        call(terminal, args("action", "monitor", "session_id", id, "monitor",
                operation("pause").put("monitor_id", "monitor-1")));
        call(terminal, args("action", "monitor", "session_id", id, "monitor",
                operation("resume").put("monitor_id", "monitor-1")));
        ObjectNode update = operation("update").put("monitor_id", "monitor-1");
        update.set("definition", stateDefinition("replacement"));
        assertEquals("monitor-1", call(terminal, args("action", "monitor", "session_id", id,
                "monitor", update)).path("success").path("monitor").path("monitor_id").asText());
        call(terminal, args("action", "monitor", "session_id", id, "monitor",
                operation("remove").put("monitor_id", "monitor-1")));

        ObjectNode escaped = operation("add");
        ObjectNode pathCondition = condition("path_exists").put("path", workspace.resolveSibling("outside" ).toString());
        ObjectNode pathDefinition = definition(pathCondition, "until_match").put("check_interval_ms", 10);
        escaped.set("definition", pathDefinition);
        assertThrows(Exception.class, () -> terminal.execute(args("action", "monitor", "session_id", id,
                "monitor", escaped)));

        JsonNode inspected = call(terminal, args("action", "inspect", "session_id", id));
        assertEquals(0, inspected.path("success").path("inspect").path("session")
                .path("active_monitor_count").asInt());
        assertTrue(inspected.path("success").path("inspect").path("events").size() >= 2);
        call(terminal, args("action", "close", "session_id", id, "close_policy", "force"));
    }

    private ObjectNode stateDefinition(String pattern) {
        ObjectNode result = definition(condition("output_contains").put("pattern", pattern), "until_session_end");
        result.set("notify", json.createObjectNode().put("kind", "on_state_change"));
        return result;
    }

    private ObjectNode definition(ObjectNode condition, String lifetime) {
        ObjectNode result = json.createObjectNode();
        result.set("condition", condition);
        result.set("notify", json.createObjectNode().put("kind", "on_match"));
        result.set("lifetime", json.createObjectNode().put("kind", lifetime));
        return result;
    }

    private ObjectNode condition(String kind) { return json.createObjectNode().put("kind", kind); }
    private ObjectNode operation(String kind) { return json.createObjectNode().put("kind", kind); }

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
            if (value instanceof JsonNode node) result.set((String) values[index], node);
            else if (value instanceof Integer number) result.put((String) values[index], number);
            else if (value instanceof Long number) result.put((String) values[index], number);
            else result.put((String) values[index], String.valueOf(value));
        }
        return result;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
