package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Strict decoder/defaulting for fx's six-branch public subagent command. */
record SubagentCommand(String branch, ObjectNode value) {
    static final int MAX_NAME_BYTES = 128;
    static final int MAX_MODEL_BYTES = 256;
    static final int MAX_PROMPT_BYTES = 64 * 1024;
    static final int MAX_MESSAGE_BYTES = 64 * 1024;
    static final int MAX_PAGE_LIMIT = 100;
    static final long MAX_INSPECT_WAIT_MS = 60_000;
    private static final Set<String> BRANCHES = Set.of(
            "create", "inspect", "message", "relationship", "configure", "lifecycle");
    private static final Set<String> SECTIONS = Set.of(
            "status", "messages", "tool_activity", "events", "configuration", "relationship");

    static SubagentCommand parse(JsonNode arguments) {
        requireObject(arguments, "invalid_root");
        exact(arguments, Set.of("command"));
        JsonNode command = required(arguments, "command", "missing_command");
        requireObject(command, "invalid_field_type");
        exact(command, BRANCHES);
        if (command.size() != 1) throw invalid("invalid_branch_selection");
        String branch = command.fieldNames().next();
        JsonNode selected = command.path(branch);
        requireObject(selected, "invalid_field_type");
        ObjectNode value = ((ObjectNode) selected).deepCopy();
        switch (branch) {
            case "create" -> validateCreate(value);
            case "inspect" -> validateInspect(value);
            case "message" -> validateMessage(value);
            case "relationship" -> validateRelationship(value);
            case "configure" -> validateConfigure(value);
            case "lifecycle" -> validateLifecycle(value);
            default -> throw invalid("invalid_branch_selection");
        }
        return new SubagentCommand(branch, value);
    }

    private static void validateCreate(ObjectNode value) {
        exact(value, Set.of("name", "mode", "prompt", "model", "effort", "permission_mode", "notifications"));
        bounded(requiredText(value, "name", "missing_name"), MAX_NAME_BYTES, "invalid_name");
        String mode = requiredText(value, "mode", "missing_mode");
        if (!Set.of("one_off", "persistent").contains(mode)) throw invalid("invalid_enum");
        if (mode.equals("one_off") && !value.has("prompt")) throw invalid("missing_one_off_prompt");
        if (value.has("prompt")) bounded(text(value, "prompt"), MAX_PROMPT_BYTES, "invalid_prompt");
        if (value.has("model")) bounded(text(value, "model"), MAX_MODEL_BYTES, "invalid_model");
        validateEffortAndPermission(value);
        if (value.has("notifications")) validateNotifications(value.path("notifications"));
    }

    private static void validateInspect(ObjectNode value) {
        exact(value, Set.of("id", "sections", "cursor", "limit", "wait"));
        validateId(requiredText(value, "id", "missing_inspect_id"));
        JsonNode sections = required(value, "sections", "invalid_inspect_sections");
        if (!sections.isArray() || sections.isEmpty() || sections.size() > SECTIONS.size()) {
            throw invalid("invalid_inspect_sections");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode section : sections) {
            if (!section.isTextual() || !SECTIONS.contains(section.asText()) || !unique.add(section.asText())) {
                throw invalid("invalid_inspect_sections");
            }
        }
        if (value.has("cursor") && !text(value, "cursor").matches("v1:[0-9]+:[0-9]+")) {
            throw invalid("invalid_cursor");
        }
        if (value.has("limit")) integer(value.path("limit"), 1, MAX_PAGE_LIMIT, "invalid_page_limit");
        if (value.has("wait")) {
            JsonNode wait = value.path("wait");
            requireObject(wait, "invalid_field_type");
            exact(wait, Set.of("until", "after_generation", "timeout_ms"));
            if (value.has("cursor") || !unique.contains("status")
                    || !requiredText(wait, "until", "invalid_inspect_wait").equals("settled")
                    || !wait.has("timeout_ms")) throw invalid("invalid_inspect_wait");
            integer(wait.path("timeout_ms"), 1, MAX_INSPECT_WAIT_MS, "invalid_inspect_wait");
            if (wait.has("after_generation")) integer(wait.path("after_generation"), 0, Long.MAX_VALUE,
                    "invalid_inspect_wait");
        }
    }

    private static void validateMessage(ObjectNode value) {
        exact(value, Set.of("send", "milestone"));
        if (value.size() != 1) throw invalid("invalid_nested_branch_selection");
        if (value.has("send")) {
            JsonNode send = value.path("send");
            requireObject(send, "invalid_field_type");
            exact(send, Set.of("id", "content"));
            validateId(requiredText(send, "id", "missing_field"));
            bounded(requiredText(send, "content", "missing_field"), MAX_MESSAGE_BYTES, "invalid_message");
        } else {
            JsonNode milestone = value.path("milestone");
            requireObject(milestone, "invalid_field_type");
            exact(milestone, Set.of("name"));
            bounded(requiredText(milestone, "name", "missing_field"), MAX_NAME_BYTES, "invalid_name");
        }
    }

    private static void validateRelationship(ObjectNode value) {
        exact(value, Set.of("action", "id", "parent_id"));
        String action = requiredText(value, "action", "missing_field");
        if (!Set.of("attach", "detach", "reparent").contains(action)) throw invalid("invalid_enum");
        String id = requiredText(value, "id", "missing_field");
        validateId(id);
        String parent = value.has("parent_id") ? text(value, "parent_id") : null;
        if (parent != null) validateId(parent);
        if ((action.equals("detach") && parent != null) || (action.equals("reparent") && parent == null)
                || (parent != null && parent.equals(id))) throw invalid("invalid_relationship");
    }

    private static void validateConfigure(ObjectNode value) {
        exact(value, Set.of("id", "name", "model", "effort", "permission_mode", "notifications"));
        validateId(requiredText(value, "id", "missing_field"));
        if (value.size() == 1) throw invalid("empty_configuration");
        if (value.has("name")) bounded(text(value, "name"), MAX_NAME_BYTES, "invalid_name");
        if (value.has("model")) bounded(text(value, "model"), MAX_MODEL_BYTES, "invalid_model");
        validateEffortAndPermission(value);
        if (value.has("notifications")) validateNotifications(value.path("notifications"));
    }

    private static void validateLifecycle(ObjectNode value) {
        exact(value, Set.of("id", "action"));
        validateId(requiredText(value, "id", "missing_field"));
        if (!Set.of("cancel", "resume", "close", "reopen")
                .contains(requiredText(value, "action", "missing_field"))) throw invalid("invalid_enum");
    }

    private static void validateEffortAndPermission(ObjectNode value) {
        if (value.has("effort")) {
            String effort = text(value, "effort");
            if (effort.getBytes(StandardCharsets.UTF_8).length > 64
                    || !effort.matches("[A-Za-z0-9_.-]+")) throw invalid("invalid_enum");
        }
        if (value.has("permission_mode")
                && !Set.of("ask", "auto", "yolo").contains(text(value, "permission_mode"))) {
            throw invalid("invalid_enum");
        }
    }

    private static void validateNotifications(JsonNode input) {
        requireObject(input, "invalid_field_type");
        exact(input, Set.of("terminal", "milestones", "report_interval_ms", "report_duration_ms",
                "stop_conditions"));
        if (input.has("terminal")) {
            JsonNode terminal = input.path("terminal");
            requireObject(terminal, "invalid_field_type");
            exact(terminal, Set.of("completed", "failed", "cancelled"));
            terminal.forEach(item -> { if (!item.isBoolean()) throw invalid("invalid_field_type"); });
        }
        if (input.has("milestones")) uniqueNames(input.path("milestones"), 32, "duplicate_milestone");
        if (input.has("report_interval_ms")) integer(input.path("report_interval_ms"), 1, Long.MAX_VALUE,
                "invalid_notification_policy");
        if (input.has("report_duration_ms")) {
            integer(input.path("report_duration_ms"), 1, Long.MAX_VALUE, "invalid_notification_policy");
            if (!input.has("report_interval_ms")) throw invalid("invalid_notification_policy");
        }
        if (input.has("stop_conditions")) {
            JsonNode stops = input.path("stop_conditions");
            if (!stops.isArray() || stops.size() > 8) throw invalid("invalid_notification_policy");
            Set<String> unique = new HashSet<>();
            for (JsonNode stop : stops) {
                if (!stop.isTextual() || !Set.of("terminal", "duration_elapsed").contains(stop.asText())) {
                    throw invalid("invalid_notification_policy");
                }
                if (!unique.add(stop.asText())) throw invalid("duplicate_stop_condition");
                if (stop.asText().equals("duration_elapsed") && !input.has("report_duration_ms")) {
                    throw invalid("invalid_notification_policy");
                }
            }
        }
    }

    private static void uniqueNames(JsonNode values, int maximum, String duplicateCode) {
        if (!values.isArray() || values.size() > maximum) throw invalid("invalid_notification_policy");
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw invalid("invalid_field_type");
            bounded(value.asText(), MAX_NAME_BYTES, "invalid_name");
            if (!unique.add(value.asText())) throw invalid(duplicateCode);
        }
    }

    static void validateId(String id) {
        if (id.isEmpty() || id.length() > 255 || id.equals(".") || id.equals("..")
                || !id.matches("[A-Za-z0-9._-]+")) throw invalid("invalid_id");
    }

    private static void bounded(String text, int maximumBytes, String code) {
        if (text.isEmpty() || text.indexOf('\0') >= 0
                || text.getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw invalid(code);
    }

    private static long integer(JsonNode value, long minimum, long maximum, String code) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < minimum || value.asLong() > maximum) throw invalid(code);
        return value.asLong();
    }

    private static String requiredText(JsonNode input, String field, String code) {
        JsonNode value = input.get(field);
        if (value == null) throw invalid(code);
        if (!value.isTextual()) throw invalid("invalid_field_type");
        return value.asText();
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual()) throw invalid("invalid_field_type");
        return value.asText();
    }

    private static JsonNode required(JsonNode input, String field, String code) {
        JsonNode value = input.get(field);
        if (value == null) throw invalid(code);
        return value;
    }

    private static void requireObject(JsonNode value, String code) {
        if (value == null || !value.isObject()) throw invalid(code);
    }

    private static void exact(JsonNode input, Set<String> allowed) {
        Iterator<String> fields = input.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid("unknown_field");
    }

    static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException("subagent rejected: " + code);
    }
}
