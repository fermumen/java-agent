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

    String preview(JsonNode arguments);

    default String execute(JsonNode arguments, String invocationId) throws Exception {
        return execute(arguments);
    }

    String execute(JsonNode arguments) throws Exception;
}
