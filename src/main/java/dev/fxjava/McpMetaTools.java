package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** fx-compatible MCP metadata and feature tools over the compact runtime. */
final class McpMetaTools {
    private McpMetaTools() { }

    private static final class Match {
        private final McpRuntime.McpToolInfo info;
        private final int score;

        private Match(McpRuntime.McpToolInfo info, int score) {
            this.info = info;
            this.score = score;
        }

        public McpRuntime.McpToolInfo info() { return info; }
        public int score() { return score; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Match that = (Match) other;
            return score == that.score && Objects.equals(info, that.info);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(info) + Integer.hashCode(score);
        }

        @Override
        public String toString() {
            return "Match[info=" + info + ", score=" + score + "]";
        }
    }

    static List<Tool> create(McpRuntime runtime) {
        return List.of(new Search(runtime), new Select(runtime), new Features(runtime));
    }

    private abstract static class MetaTool implements Tool {
        final McpRuntime runtime;
        final ObjectNode parameters;

        MetaTool(McpRuntime runtime, ObjectNode parameters) {
            this.runtime = runtime;
            this.parameters = parameters;
        }

        @Override public ObjectNode parameters() { return parameters.deepCopy(); }
        @Override public boolean requiresApproval() { return false; }
    }

    private static final class Search extends MetaTool {
        Search(McpRuntime runtime) {
            super(runtime, searchSchema(runtime));
        }

        @Override public String name() { return "mcp_search_tools"; }
        @Override public String description() {
            return "Search configured MCP tool names, descriptions, servers, and input schemas.";
        }
        @Override public String preview(JsonNode arguments) {
            return "search MCP tools for " + arguments.path("query").asText("?");
        }
        @Override public String execute(JsonNode arguments) throws Exception {
            String query = required(arguments, "query").toLowerCase(Locale.ROOT);
            int limit = integer(arguments, "limit", 8, 1, 32);
            List<Match> matches = new ArrayList<>();
            for (McpRuntime.McpToolInfo info : runtime.toolCatalog()) {
                String name = info.publicName().toLowerCase(Locale.ROOT);
                String haystack = (info.publicName() + " " + info.server() + " " + info.remoteName()
                        + " " + info.description() + " " + info.schema()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
                int score = name.equals(query) ? 3 : name.contains(query) ? 2 : 1;
                matches.add(new Match(info, score));
            }
            matches.sort(Comparator.comparingInt(Match::score).reversed()
                    .thenComparing(match -> match.info().publicName()));
            ObjectNode result = runtime.json().createObjectNode();
            var found = result.putArray("tools");
            for (int index = 0; index < Math.min(limit, matches.size()); index++) {
                McpRuntime.McpToolInfo info = matches.get(index).info();
                found.addObject().put("name", info.publicName()).put("server", info.server())
                        .put("description", info.description()).put("read_only", info.readOnly())
                        .set("input_schema", info.schema());
            }
            result.put("more_available", matches.size() > limit);
            return runtime.bounded(result);
        }
    }

    private static final class Select extends MetaTool implements DynamicToolProvider {
        Select(McpRuntime runtime) {
            super(runtime, oneStringSchema(runtime, "name", "Exact dynamic MCP tool name"));
        }

        @Override public String name() { return "mcp_select_tool"; }
        @Override public String description() {
            return "Select one exact MCP tool discovered with mcp_search_tools and return its executable schema.";
        }
        @Override public String preview(JsonNode arguments) {
            return "select MCP tool " + arguments.path("name").asText("?");
        }
        @Override public String execute(JsonNode arguments) throws Exception {
            String name = required(arguments, "name");
            for (McpRuntime.McpToolInfo info : runtime.toolCatalog()) {
                if (!info.publicName().equals(name)) continue;
                runtime.selectTool(name);
                ObjectNode result = runtime.json().createObjectNode().put("name", info.publicName())
                        .put("server", info.server()).put("description", info.description())
                        .put("read_only", info.readOnly()).put("advertised", true);
                result.set("input_schema", info.schema());
                return runtime.bounded(result);
            }
            throw new IOException("Unknown MCP tool: " + name);
        }

        @Override public Tool resolveDynamicTool(String name) throws IOException {
            return runtime.dynamicTool(name);
        }

        @Override public List<Tool> dynamicTools() throws IOException {
            return runtime.selectedTools();
        }
    }

    private static final class Features extends MetaTool {
        Features(McpRuntime runtime) {
            super(runtime, featureSchema(runtime));
        }

        @Override public String name() { return "mcp_features"; }
        @Override public String description() {
            return "List/read MCP resources, list/get prompts, and complete prompt or resource-template arguments.";
        }
        @Override public String preview(JsonNode arguments) {
            return "MCP " + arguments.path("action").asText("feature") + " on "
                    + arguments.path("server").asText("?");
        }
        @Override public String execute(JsonNode arguments) throws Exception {
            String action = required(arguments, "action");
            String server = required(arguments, "server");
            JsonNode result;
            switch (action) {
                case "resource_list":
                    result = runtime.pagedFeature(server, "resources/list", "resources");
                    break;
                case "resource_templates":
                    result = runtime.pagedFeature(server, "resources/templates/list", "resourceTemplates");
                    break;
                case "prompt_list":
                    result = runtime.pagedFeature(server, "prompts/list", "prompts");
                    break;
                case "resource_read":
                    result = runtime.featureRequest(server, "resources/read",
                            runtime.json().createObjectNode().put("uri", required(arguments, "uri")));
                    break;
                case "prompt_get":
                    result = promptGet(server, arguments);
                    break;
                case "prompt_complete":
                    result = complete(server, arguments, true);
                    break;
                case "resource_complete":
                    result = complete(server, arguments, false);
                    break;
                default:
                    throw new IOException("Unknown MCP feature action: " + action);
            }
            return runtime.bounded(result);
        }

        private JsonNode promptGet(String server, JsonNode arguments) throws IOException {
            ObjectNode params = runtime.json().createObjectNode().put("name", required(arguments, "prompt"));
            if (arguments.has("arguments") && !arguments.path("arguments").isNull()) {
                params.set("arguments", stringMap(arguments.path("arguments"), "arguments"));
            }
            return runtime.featureRequest(server, "prompts/get", params);
        }

        private JsonNode complete(String server, JsonNode arguments, boolean prompt) throws IOException {
            ObjectNode params = runtime.json().createObjectNode();
            ObjectNode reference = params.putObject("ref");
            if (prompt) reference.put("type", "ref/prompt").put("name", required(arguments, "prompt"));
            else reference.put("type", "ref/resource").put("uri", required(arguments, "uri_template"));
            params.putObject("argument").put("name", required(arguments, "argument"))
                    .put("value", arguments.path("value").asText(""));
            if (arguments.has("context") && !arguments.path("context").isNull()) {
                params.putObject("context").set("arguments", stringMap(arguments.path("context"), "context"));
            }
            return runtime.featureRequest(server, "completion/complete", params);
        }
    }

    private static ObjectNode searchSchema(McpRuntime runtime) {
        ObjectNode schema = objectSchema(runtime);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string");
        properties.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 32);
        schema.putArray("required").add("query");
        return schema;
    }

    private static ObjectNode oneStringSchema(McpRuntime runtime, String field, String description) {
        ObjectNode schema = objectSchema(runtime);
        schema.putObject("properties").putObject(field).put("type", "string").put("description", description);
        schema.putArray("required").add(field);
        return schema;
    }

    private static ObjectNode featureSchema(McpRuntime runtime) {
        ObjectNode schema = objectSchema(runtime);
        ObjectNode properties = schema.putObject("properties");
        var actions = properties.putObject("action").put("type", "string").putArray("enum");
        for (String action : List.of("resource_list", "resource_templates", "resource_read", "prompt_list",
                "prompt_get", "prompt_complete", "resource_complete")) actions.add(action);
        for (String field : List.of("server", "uri", "uri_template", "prompt", "argument", "value")) {
            properties.putObject(field).put("type", "string");
        }
        properties.putObject("arguments").put("type", "object");
        properties.putObject("context").put("type", "object");
        schema.putArray("required").add("action").add("server");
        return schema;
    }

    private static ObjectNode objectSchema(McpRuntime runtime) {
        return runtime.json().createObjectNode().put("type", "object").put("additionalProperties", false);
    }

    private static ObjectNode stringMap(JsonNode value, String field) throws IOException {
        if (!value.isObject() || value.size() > 128) throw new IOException(field + " must be an object of strings");
        ObjectNode copy = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        for (var entry : value.properties()) {
            if (!entry.getValue().isTextual()) throw new IOException(field + " values must be strings");
            copy.put(entry.getKey(), entry.getValue().asText());
        }
        return copy;
    }

    private static String required(JsonNode arguments, String field) throws IOException {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IOException(field + " is required");
        return value.asText();
    }

    private static int integer(JsonNode arguments, String field, int fallback, int minimum, int maximum)
            throws IOException {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt() || value.asInt() < minimum || value.asInt() > maximum) {
            throw new IOException(field + " must be between " + minimum + " and " + maximum);
        }
        return value.asInt();
    }
}
