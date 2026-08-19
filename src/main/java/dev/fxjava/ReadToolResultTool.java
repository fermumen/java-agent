package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/** Read-only bounded accessor for large session-scoped tool-result sidecars. */
final class ReadToolResultTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolResultStore store;
    private final ObjectNode parameters;

    ReadToolResultTool(ToolResultStore store) {
        this.store = store;
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("handle").put("type", "string");
        properties.putObject("start_byte").put("type", "integer").put("minimum", 1);
        properties.putObject("byte_count").put("type", "integer").put("minimum", 1);
        properties.putObject("query").put("type", "string");
        schema.putArray("required").add("handle");
        schema.put("additionalProperties", false);
        parameters = schema;
    }

    @Override public String name() { return "read_tool_result"; }
    @Override public String description() {
        return "Read a bounded byte range or literal line matches from a stored large tool result.";
    }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String preview(JsonNode arguments) { return "read tool result `" + arguments.path("handle").asText("?") + "`"; }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("read_tool_result arguments must be an object");
        arguments.fieldNames().forEachRemaining(field -> {
            if (!Set.of("handle", "start_byte", "byte_count", "query").contains(field)) {
                throw new IllegalArgumentException("unknown read_tool_result field: " + field);
            }
        });
        if (!arguments.path("handle").isTextual() || arguments.path("handle").asText().trim().isEmpty()) {
            throw new IllegalArgumentException("read_tool_result field \"handle\" must not be empty");
        }
        int start = positive(arguments, "start_byte", 1, Integer.MAX_VALUE);
        int count = positive(arguments, "byte_count", ToolResultStore.READ_DEFAULT_BYTES,
                ToolResultStore.READ_MAX_BYTES);
        String query = arguments.has("query") && arguments.path("query").isTextual()
                ? arguments.path("query").asText() : null;
        if (arguments.has("query") && query == null) {
            throw new IllegalArgumentException("read_tool_result field \"query\" must be a string");
        }
        return store.read(arguments.path("handle").asText(), start, count, query);
    }

    private static int positive(JsonNode input, String field, int fallback, int cap) {
        if (!input.has(field)) return fallback;
        JsonNode value = input.path(field);
        if (!value.isIntegralNumber() || value.asLong() < 1) {
            throw new IllegalArgumentException("read_tool_result field \"" + field + "\" must be a positive integer");
        }
        return (int) Math.min(value.asLong(), cap);
    }
}
