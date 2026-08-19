package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMonitorDefinitionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsEveryFxConditionKindWithItsExactScheduleClass() {
        for (String kind : new String[]{"process_exit", "exit_code", "signal", "output_contains",
                "output_matches", "output_quiet", "screen_matches", "tcp_ready", "http_ready",
                "path_exists", "path_changed", "path_size", "custom_probe"}) {
            ObjectNode definition = definition(condition(kind));
            boolean polling = switch (kind) {
                case "tcp_ready", "http_ready", "path_exists", "path_changed", "path_size", "custom_probe" -> true;
                default -> false;
            };
            if (polling) definition.put("check_interval_ms", 10);
            var parsed = TerminalMonitorDefinition.parse(definition);
            assertEquals(kind, parsed.condition().kind());
            assertEquals(polling, parsed.condition().polling());
        }
    }

    @Test
    void enforcesPollingSchedulesAndRuntimePatternBound() {
        ObjectNode missing = definition(condition("path_exists"));
        assertThrows(IllegalArgumentException.class, () -> TerminalMonitorDefinition.parse(missing));

        ObjectNode unexpected = definition(condition("process_exit")).put("check_interval_ms", 10);
        assertThrows(IllegalArgumentException.class, () -> TerminalMonitorDefinition.parse(unexpected));

        ObjectNode longPattern = condition("output_contains").put("pattern", "x".repeat(257));
        assertThrows(IllegalArgumentException.class,
                () -> TerminalMonitorDefinition.parse(definition(longPattern)));
    }

    @Test
    void enforcesNotifyLifetimeAndUnknownFieldBounds() {
        ObjectNode valid = definition(condition("output_contains"));
        valid.set("notify", json.createObjectNode().put("kind", "every_n_checks").put("count", 2));
        valid.set("lifetime", json.createObjectNode().put("kind", "duration").put("duration_ms", 500));
        var parsed = TerminalMonitorDefinition.parse(valid);
        assertEquals(2, parsed.notification().value());
        assertEquals(500, parsed.lifetime().durationMs());
        assertFalse(parsed.condition().polling());

        valid.put("authority", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> TerminalMonitorDefinition.parse(valid));
        assertTrue(TerminalMonitorDefinition.MAX_LIFETIME_MS > TerminalMonitorDefinition.MAX_SCHEDULE_MS);
    }

    private ObjectNode definition(ObjectNode condition) {
        ObjectNode result = json.createObjectNode();
        result.set("condition", condition);
        result.set("notify", json.createObjectNode().put("kind", "on_match"));
        result.set("lifetime", json.createObjectNode().put("kind", "until_match"));
        return result;
    }

    private ObjectNode condition(String kind) {
        ObjectNode result = json.createObjectNode().put("kind", kind);
        switch (kind) {
            case "exit_code" -> result.put("exit_code", 7);
            case "signal" -> result.put("signal", "terminate");
            case "output_contains", "output_matches", "screen_matches" -> result.put("pattern", "ready");
            case "output_quiet" -> result.put("duration_ms", 10);
            case "tcp_ready" -> result.put("host", "127.0.0.1").put("port", 3000);
            case "http_ready" -> result.put("pattern", "http://127.0.0.1/ready");
            case "path_exists", "path_changed" -> result.put("path", "ready.txt");
            case "path_size" -> result.put("path", "ready.txt").put("minimum_bytes", 1);
            case "custom_probe" -> result.put("command", "echo ready").put("cwd", ".");
            default -> { }
        }
        return result;
    }
}
