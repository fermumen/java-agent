package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    String name();

    String description();

    ObjectNode parameters();

    boolean requiresApproval();

    String preview(JsonNode arguments);

    String execute(JsonNode arguments) throws Exception;
}
