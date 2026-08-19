package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/** One compact asynchronous API for ordinary child-session management. */
final class SubagentTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SubagentManager manager;
    private final ObjectNode parameters;

    SubagentTool(SubagentManager manager) {
        this.manager = manager;
        parameters = schema();
    }

    @Override public String name() { return "subagent"; }
    @Override public String description() {
        return "Create, inspect, message, relate, configure, or control bounded asynchronous child sessions. "
                + "Select exactly one command branch; use inspect.wait when this turn needs a settled result.";
    }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String preview(JsonNode arguments) {
        JsonNode command = arguments.path("command");
        return "subagent " + (command.isObject() && command.fieldNames().hasNext()
                ? command.fieldNames().next() : "?");
    }
    @Override public boolean isErrorResult(String result) { return result.startsWith("{\"ok\":false"); }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        return execute(arguments, "call-" + UUID.randomUUID());
    }

    @Override
    public String execute(JsonNode arguments, String invocationId) throws Exception {
        try {
            return manager.execute(SubagentCommand.parse(arguments), invocationId);
        } catch (IllegalArgumentException rejected) {
            String code = rejected.getMessage();
            int marker = code == null ? -1 : code.lastIndexOf(": ");
            if (marker >= 0) code = code.substring(marker + 2);
            ObjectNode result = JSON.createObjectNode().put("ok", false).put("operation_id", invocationId);
            result.putNull("child_id").put("status", "rejected").put("error_code", code == null ? "invalid_root" : code)
                    .put("retryable", false).putNull("requested").putNull("cursor");
            return JSON.writeValueAsString(result);
        }
    }

    private static ObjectNode schema() {
        ObjectNode root = object();
        ObjectNode command = object();
        root.withObject("properties").set("command", command);
        root.putArray("required").add("command");
        ObjectNode branches = command.withObject("properties");

        ObjectNode create = object();
        properties(create, "name", string(), "mode", enumeration("one_off", "persistent"),
                "prompt", string(), "model", string(), "effort", string(),
                "permission_mode", enumeration("ask", "auto", "yolo"), "notifications", object());
        create.putArray("required").add("name").add("mode");
        branches.set("create", create);

        ObjectNode inspect = object();
        properties(inspect, "id", string(), "sections", array(enumeration("status", "messages", "tool_activity",
                        "events", "configuration", "relationship")), "cursor", string(), "limit", integer(1, 100),
                "wait", object());
        inspect.putArray("required").add("id").add("sections");
        branches.set("inspect", inspect);

        ObjectNode send = object();
        properties(send, "id", string(), "content", string());
        send.putArray("required").add("id").add("content");
        ObjectNode milestone = object();
        properties(milestone, "name", string());
        milestone.putArray("required").add("name");
        ObjectNode message = object();
        properties(message, "send", send, "milestone", milestone);
        branches.set("message", message);

        ObjectNode relationship = object();
        properties(relationship, "action", enumeration("attach", "detach", "reparent"),
                "id", string(), "parent_id", string());
        relationship.putArray("required").add("action").add("id");
        branches.set("relationship", relationship);

        ObjectNode configure = object();
        properties(configure, "id", string(), "name", string(), "model", string(), "effort", string(),
                "permission_mode", enumeration("ask", "auto", "yolo"), "notifications", object());
        configure.putArray("required").add("id");
        branches.set("configure", configure);

        ObjectNode lifecycle = object();
        properties(lifecycle, "id", string(), "action", enumeration("cancel", "resume", "close", "reopen"));
        lifecycle.putArray("required").add("id").add("action");
        branches.set("lifecycle", lifecycle);
        return root;
    }

    private static ObjectNode object() {
        ObjectNode node = JSON.createObjectNode().put("type", "object");
        node.putObject("properties");
        node.put("additionalProperties", false);
        return node;
    }

    private static ObjectNode string() { return JSON.createObjectNode().put("type", "string"); }
    private static ObjectNode integer(int minimum, int maximum) {
        return JSON.createObjectNode().put("type", "integer").put("minimum", minimum).put("maximum", maximum);
    }
    private static ObjectNode enumeration(String... values) {
        ObjectNode result = string();
        ArrayNode allowed = result.putArray("enum");
        List.of(values).forEach(allowed::add);
        return result;
    }
    private static ObjectNode array(ObjectNode items) {
        ObjectNode result = JSON.createObjectNode().put("type", "array");
        result.set("items", items);
        return result;
    }
    private static void properties(ObjectNode target, Object... pairs) {
        ObjectNode properties = target.withObject("properties");
        for (int index = 0; index < pairs.length; index += 2) {
            properties.set((String) pairs[index], (JsonNode) pairs[index + 1]);
        }
    }
}
