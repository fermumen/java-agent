package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Strict decoder for fx's compact public terminal monitor definition. */
final class TerminalMonitorDefinition {
    static final long MIN_SCHEDULE_MS = 10;
    static final long MAX_SCHEDULE_MS = 24L * 60 * 60 * 1000;
    static final long MAX_LIFETIME_MS = 365L * 24 * 60 * 60 * 1000;
    static final int MAX_PATTERN_BYTES = 256;

    private final Condition condition;
    private final Long checkIntervalMs;
    private final Notify notification;
    private final Lifetime lifetime;
    private final JsonNode source;

    TerminalMonitorDefinition(Condition condition, Long checkIntervalMs, Notify notification,
                              Lifetime lifetime, JsonNode source) {
        this.condition = condition;
        this.checkIntervalMs = checkIntervalMs;
        this.notification = notification;
        this.lifetime = lifetime;
        this.source = source;
    }

    public Condition condition() { return condition; }
    public Long checkIntervalMs() { return checkIntervalMs; }
    public Notify notification() { return notification; }
    public Lifetime lifetime() { return lifetime; }
    public JsonNode source() { return source; }

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

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TerminalMonitorDefinition)) return false;
        TerminalMonitorDefinition that = (TerminalMonitorDefinition) other;
        return Objects.equals(condition, that.condition)
                && Objects.equals(checkIntervalMs, that.checkIntervalMs)
                && Objects.equals(notification, that.notification)
                && Objects.equals(lifetime, that.lifetime)
                && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(condition);
        result = 31 * result + Objects.hashCode(checkIntervalMs);
        result = 31 * result + Objects.hashCode(notification);
        result = 31 * result + Objects.hashCode(lifetime);
        return 31 * result + Objects.hashCode(source);
    }

    @Override
    public String toString() {
        return "TerminalMonitorDefinition[condition=" + condition
                + ", checkIntervalMs=" + checkIntervalMs
                + ", notification=" + notification
                + ", lifetime=" + lifetime
                + ", source=" + source + "]";
    }

    static final class Condition {
        private static final Set<String> KINDS = Set.of("process_exit", "exit_code", "signal",
                "output_contains", "output_matches", "output_quiet", "screen_matches", "tcp_ready",
                "http_ready", "path_exists", "path_changed", "path_size", "custom_probe");
        private static final Set<String> SIGNALS = Set.of("hangup", "interrupt", "quit", "terminate", "kill");

        private final String kind;
        private final String text;
        private final Long number;
        private final String secondary;
        private final Integer port;

        Condition(String kind, String text, Long number, String secondary, Integer port) {
            this.kind = kind;
            this.text = text;
            this.number = number;
            this.secondary = secondary;
            this.port = port;
        }

        public String kind() { return kind; }
        public String text() { return text; }
        public Long number() { return number; }
        public String secondary() { return secondary; }
        public Integer port() { return port; }

        static Condition parse(JsonNode value) {
            requireObject(value, "monitor condition");
            exact(value, Set.of("kind", "pattern", "duration_ms", "exit_code", "signal", "host",
                    "port", "path", "minimum_bytes", "command", "cwd"));
            String kind = requiredText(value, "kind", 64);
            if (!KINDS.contains(kind)) throw invalid("InvalidMonitorCondition");
            switch (kind) {
                case "process_exit":
                    return new Condition(kind, null, null, null, null);
                case "exit_code":
                    return new Condition(kind, null, signedInt(value, "exit_code"), null, null);
                case "signal":
                    String signal = requiredText(value, "signal", 32);
                    if (!SIGNALS.contains(signal)) throw invalid("InvalidMonitorCondition");
                    return new Condition(kind, signal, null, null, null);
                case "output_contains":
                case "output_matches":
                case "screen_matches":
                    return new Condition(kind, requiredText(value, "pattern", MAX_PATTERN_BYTES), null, null, null);
                case "output_quiet":
                    return new Condition(kind, null,
                            requiredLong(value, "duration_ms", MIN_SCHEDULE_MS, MAX_SCHEDULE_MS), null, null);
                case "tcp_ready":
                    return new Condition(kind, requiredText(value, "host", 4096), null, null,
                            Math.toIntExact(requiredLong(value, "port", 1, 65535)));
                case "http_ready":
                    return new Condition(kind, requiredText(value, "pattern", 4096), null, null, null);
                case "path_exists":
                case "path_changed":
                    return new Condition(kind, requiredText(value, "path", 4096), null, null, null);
                case "path_size":
                    return new Condition(kind, requiredText(value, "path", 4096),
                            requiredLong(value, "minimum_bytes", 0, Long.MAX_VALUE), null, null);
                case "custom_probe":
                    return new Condition(kind, requiredText(value, "command", 64 * 1024),
                            null, requiredText(value, "cwd", 4096), null);
                default:
                    throw invalid("InvalidMonitorCondition");
            }
        }

        boolean polling() {
            return Set.of("tcp_ready", "http_ready", "path_exists", "path_changed", "path_size",
                    "custom_probe").contains(kind);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Condition)) return false;
            Condition that = (Condition) other;
            return Objects.equals(kind, that.kind) && Objects.equals(text, that.text)
                    && Objects.equals(number, that.number) && Objects.equals(secondary, that.secondary)
                    && Objects.equals(port, that.port);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(kind);
            result = 31 * result + Objects.hashCode(text);
            result = 31 * result + Objects.hashCode(number);
            result = 31 * result + Objects.hashCode(secondary);
            return 31 * result + Objects.hashCode(port);
        }

        @Override
        public String toString() {
            return "Condition[kind=" + kind + ", text=" + text + ", number=" + number
                    + ", secondary=" + secondary + ", port=" + port + "]";
        }
    }

    static final class Notify {
        private final String kind;
        private final Long value;

        Notify(String kind, Long value) {
            this.kind = kind;
            this.value = value;
        }

        public String kind() { return kind; }
        public Long value() { return value; }

        static Notify parse(JsonNode input) {
            requireObject(input, "monitor notify");
            exact(input, Set.of("kind", "count", "interval_ms"));
            String kind = requiredText(input, "kind", 64);
            switch (kind) {
                case "on_match":
                case "on_state_change":
                case "on_exit":
                case "every_check":
                    return new Notify(kind, null);
                case "every_n_checks":
                    return new Notify(kind, requiredLong(input, "count", 1, 1_000_000));
                case "interval":
                    return new Notify(kind,
                            requiredLong(input, "interval_ms", MIN_SCHEDULE_MS, MAX_SCHEDULE_MS));
                default:
                    throw invalid("InvalidSchedule");
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Notify)) return false;
            Notify that = (Notify) other;
            return Objects.equals(kind, that.kind) && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(kind) + Objects.hashCode(value);
        }

        @Override
        public String toString() {
            return "Notify[kind=" + kind + ", value=" + value + "]";
        }
    }

    static final class Lifetime {
        private final String kind;
        private final Long durationMs;

        Lifetime(String kind, Long durationMs) {
            this.kind = kind;
            this.durationMs = durationMs;
        }

        public String kind() { return kind; }
        public Long durationMs() { return durationMs; }

        static Lifetime parse(JsonNode input) {
            requireObject(input, "monitor lifetime");
            exact(input, Set.of("kind", "duration_ms"));
            String kind = requiredText(input, "kind", 64);
            switch (kind) {
                case "until_match":
                case "until_session_end":
                    return new Lifetime(kind, null);
                case "duration":
                    return new Lifetime(kind, requiredLong(input, "duration_ms", 1, MAX_LIFETIME_MS));
                default:
                    throw invalid("InvalidMonitorLifetime");
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Lifetime)) return false;
            Lifetime that = (Lifetime) other;
            return Objects.equals(kind, that.kind) && Objects.equals(durationMs, that.durationMs);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(kind) + Objects.hashCode(durationMs);
        }

        @Override
        public String toString() {
            return "Lifetime[kind=" + kind + ", durationMs=" + durationMs + "]";
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
