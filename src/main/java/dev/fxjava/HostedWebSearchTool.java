package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** OpenAI-hosted Responses web search; output arrives in the same response. */
final class HostedWebSearchTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public String name() { return "web_search"; }
    @Override public String description() { return "Search the public web using the Responses API hosted tool."; }
    @Override public ObjectNode parameters() { return JSON.createObjectNode().put("type", "object"); }
    @Override public boolean requiresApproval() { return false; }
    @Override public String preview(JsonNode arguments) { return "hosted web search"; }
    @Override public String execute(JsonNode arguments) { throw new IllegalStateException("hosted tool executes remotely"); }
    @Override public ObjectNode definition(ObjectMapper json) {
        return json.createObjectNode().put("type", "web_search");
    }
}
