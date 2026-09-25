package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    String name();

    String description();

    ObjectNode parameters();

    boolean requiresApproval();

    default boolean advertised() {
        return true;
    }

    default boolean requiresApproval(JsonNode arguments) throws Exception {
        return requiresApproval();
    }

    default boolean autoApprove(JsonNode arguments) throws Exception {
        return false;
    }

    default ObjectNode definition(ObjectMapper json) {
        ObjectNode definition = json.createObjectNode().put("type", "function")
                .put("name", name()).put("description", description());
        definition.set("parameters", parameters().deepCopy());
        return definition;
    }

    default boolean isErrorResult(String result) {
        return result.startsWith("Error:");
    }

    /**
     * Structured internal outcome used by the agent loop. Existing tools keep
     * their string format; tools with richer status semantics can override
     * this without smuggling status through incidental output text.
     */
    default ToolResult executeResult(JsonNode arguments, String invocationId) throws Exception {
        String output = execute(arguments, invocationId);
        return isErrorResult(output) ? ToolResult.error(output) : ToolResult.success(output);
    }

    String preview(JsonNode arguments);

    default String execute(JsonNode arguments, String invocationId) throws Exception {
        return execute(arguments);
    }

    String execute(JsonNode arguments) throws Exception;
}
