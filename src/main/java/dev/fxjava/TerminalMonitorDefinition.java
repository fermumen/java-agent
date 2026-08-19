package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Strict decoder for fx's compact public terminal monitor definition. */
record TerminalMonitorDefinition(
        Condition condition,
        Long checkIntervalMs,
        Notify notification,
        Lifetime lifetime,
        JsonNode source) {
    static final long MIN_SCHEDULE_MS = 10;
    static final long MAX_SCHEDULE_MS = 24L * 60 * 60 * 1000;
    static final long MAX_LIFETIME_MS = 365L * 24 * 60 * 60 * 1000;
    static final int MAX_PATTERN_BYTES = 256;

    static TerminalMonitorDefinition parse(JsonNode value) {
        requireObject(value, "monitor definition");
        exact(value, Set.of("condition", "check_interval_ms", "notify", "lifetime"));
        Condition condition = Condition.parse(required(value, "condition"));
        Long interval = optionalLong(value, "check_interval_ms", MIN_SCHEDULE_MS, MAX_SCHEDULE_MS);
        if (condition.polling() != (interval != null)) {
            throw invalid(condition.polling() ? "MissingCheckSchedule" : "UnexpectedCheckSchedule");
        }
        Notify notify = Notify.parse(required(value, "notify"));
        Lifetime lifetime = Lifetime.parse(required(value, "lifetime"));
        return new TerminalMonitorDefinition(condition, interval, notify, lifetime, value.deepCopy());
    }

    record Condition(String kind, String text, Long number, String secondary, Integer port) {
        private static final Set<String> KINDS = Set.of("process_exit", "exit_code", "signal",
                "output_contains", "output_matches", "output_quiet", "screen_matches", "tcp_ready",
                "http_ready", "path_exists", "path_changed", "path_size", "custom_probe");
        private static final Set<String> SIGNALS = Set.of("hangup", "interrupt", "quit", "terminate", "kill");

        static Condition parse(JsonNode value) {
            requireObject(value, "monitor condition");
            exact(value, Set.of("kind", "pattern", "duration_ms", "exit_code", "signal", "host",
                    "port", "path", "minimum_bytes", "command", "cwd"));
            String kind = requiredText(value, "kind", 64);
            if (!KINDS.contains(kind)) throw invalid("InvalidMonitorCondition");
            return switch (kind) {
                case "process_exit" -> new Condition(kind, null, null, null, null);
                case "exit_code" -> new Condition(kind, null, signedInt(value, "exit_code"), null, null);
                case "signal" -> {
                    String signal = requiredText(value, "signal", 32);
                    if (!SIGNALS.contains(signal)) throw invalid("InvalidMonitorCondition");
                    yield new Condition(kind, signal, null, null, null);
                }
                case "output_contains", "output_matches", "screen_matches" ->
                        new Condition(kind, requiredText(value, "pattern", MAX_PATTERN_BYTES), null, null, null);
                case "output_quiet" -> new Condition(kind, null,
                        requiredLong(value, "duration_ms", MIN_SCHEDULE_MS, MAX_SCHEDULE_MS), null, null);
                case "tcp_ready" -> new Condition(kind, requiredText(value, "host", 4096), null, null,
                        Math.toIntExact(requiredLong(value, "port", 1, 65535)));
                case "http_ready" -> new Condition(kind, requiredText(value, "pattern", 4096), null, null, null);
                case "path_exists", "path_changed" ->
                        new Condition(kind, requiredText(value, "path", 4096), null, null, null);
                case "path_size" -> new Condition(kind, requiredText(value, "path", 4096),
                        requiredLong(value, "minimum_bytes", 0, Long.MAX_VALUE), null, null);
                case "custom_probe" -> new Condition(kind, requiredText(value, "command", 64 * 1024),
                        null, requiredText(value, "cwd", 4096), null);
                default -> throw invalid("InvalidMonitorCondition");
            };
        }

        boolean polling() {
            return Set.of("tcp_ready", "http_ready", "path_exists", "path_changed", "path_size",
                    "custom_probe").contains(kind);
        }
    }

    record Notify(String kind, Long value) {
        static Notify parse(JsonNode input) {
            requireObject(input, "monitor notify");
            exact(input, Set.of("kind", "count", "interval_ms"));
            String kind = requiredText(input, "kind", 64);
            return switch (kind) {
                case "on_match", "on_state_change", "on_exit", "every_check" -> new Notify(kind, null);
                case "every_n_checks" -> new Notify(kind, requiredLong(input, "count", 1, 1_000_000));
                case "interval" -> new Notify(kind,
                        requiredLong(input, "interval_ms", MIN_SCHEDULE_MS, MAX_SCHEDULE_MS));
                default -> throw invalid("InvalidSchedule");
            };
        }
    }

    record Lifetime(String kind, Long durationMs) {
        static Lifetime parse(JsonNode input) {
            requireObject(input, "monitor lifetime");
            exact(input, Set.of("kind", "duration_ms"));
            String kind = requiredText(input, "kind", 64);
            return switch (kind) {
                case "until_match", "until_session_end" -> new Lifetime(kind, null);
                case "duration" -> new Lifetime(kind,
                        requiredLong(input, "duration_ms", 1, MAX_LIFETIME_MS));
                default -> throw invalid("InvalidMonitorLifetime");
            };
        }
    }

    private static JsonNode required(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) throw invalid("missing " + field);
        return value;
    }

    private static String requiredText(JsonNode input, String field, int maxBytes) {
        JsonNode value = required(input, field);
        if (!value.isTextual() || value.asText().isEmpty()
                || value.asText().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid("InvalidMonitorCondition");
        }
        return value.asText();
    }

    private static long signedInt(JsonNode input, String field) {
        JsonNode value = required(input, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid("InvalidMonitorCondition");
        return value.asInt();
    }

    private static long requiredLong(JsonNode input, String field, long minimum, long maximum) {
        JsonNode value = required(input, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < minimum || value.asLong() > maximum) throw invalid("InvalidMonitorCondition");
        return value.asLong();
    }

    private static Long optionalLong(JsonNode input, String field, long minimum, long maximum) {
        return input.has(field) ? requiredLong(input, field, minimum, maximum) : null;
    }

    private static void requireObject(JsonNode value, String name) {
        if (value == null || !value.isObject()) throw invalid(name + " must be an object");
    }

    private static void exact(JsonNode input, Set<String> fields) {
        input.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw invalid("unknown monitor field: " + field);
        });
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("terminal monitor arguments are invalid: " + reason);
    }
}
