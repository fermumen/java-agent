package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Line-delimited stdio MCP fixture used by the runtime contract tests. */
public final class FakeMcpServer {
    private FakeMcpServer() { }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        Set<String> subscriptions = new HashSet<>();
        for (String line; (line = input.readLine()) != null;) {
            JsonNode request = json.readTree(line);
            if (!request.has("id")) continue;
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            String method = request.path("method").asText();
            switch (method) {
                case "initialize":
                    initialize(response);
                    break;
                case "tools/list":
                    listTools(request, response);
                    break;
                case "tools/call":
                    callTool(request, response);
                    break;
                case "resources/list":
                    listResources(request, response);
                    break;
                case "resources/templates/list":
                    response.putObject("result").putArray("resourceTemplates")
                            .addObject().put("uriTemplate", "fixture:///{name}").put("name", "fixture");
                    break;
                case "resources/read":
                    readResource(request, response, subscriptions.size());
                    break;
                case "resources/subscribe":
                    String uri = request.path("params").path("uri").asText();
                    subscriptions.add(uri);
                    output.println(update(json, "fixture:///unsubscribed"));
                    output.println(update(json, uri));
                    response.putObject("result");
                    break;
                case "prompts/list":
                    response.putObject("result").putArray("prompts")
                            .addObject().put("name", "review").put("description", "Review prompt");
                    break;
                case "prompts/get":
                    response.putObject("result").putArray("messages")
                            .addObject().put("role", "user").putObject("content")
                            .put("type", "text").put("text", "review it");
                    break;
                case "completion/complete":
                    response.putObject("result").putObject("completion")
                            .putArray("values").add("alpha").add("beta");
                    break;
                default:
                    response.putObject("error").put("code", -32601).put("message", "unknown");
                    break;
            }
            output.println(json.writeValueAsString(response));
        }
    }

    private static void initialize(ObjectNode response) {
        ObjectNode result = response.putObject("result").put("protocolVersion", "2025-06-18");
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", true);
        capabilities.putObject("resources").put("listChanged", true).put("subscribe", true);
    }

    private static void readResource(JsonNode request, ObjectNode response, int subscriptions) {
        response.putObject("result").putArray("contents").addObject()
                .put("uri", request.path("params").path("uri").asText())
                .put("text", "resource body subscriptions=" + subscriptions);
    }

    private static String update(ObjectMapper json, String uri) throws Exception {
        ObjectNode notification = json.createObjectNode().put("jsonrpc", "2.0")
                .put("method", "notifications/resources/updated");
        notification.putObject("params").put("uri", uri);
        return json.writeValueAsString(notification);
    }

    private static void listTools(JsonNode request, ObjectNode response) {
        ObjectNode result = response.putObject("result");
        if (!request.path("params").has("cursor")) {
            ObjectNode tool = result.putArray("tools").addObject();
            tool.put("name", "zeta.echo").put("description", "Echo structured input");
            tool.putObject("annotations").put("readOnlyHint", true);
            tool.putObject("inputSchema").put("type", "object").putObject("properties")
                    .putObject("value").put("type", "string");
            result.put("nextCursor", "page-2");
        } else {
            ObjectNode tool = result.putArray("tools").addObject();
            tool.put("name", "alpha_mutate").put("description", "Mutating fixture");
            tool.putObject("inputSchema").put("type", "object");
        }
    }

    private static void listResources(JsonNode request, ObjectNode response) {
        ObjectNode result = response.putObject("result");
        if (!request.path("params").has("cursor")) {
            result.putArray("resources").addObject().put("uri", "fixture:///one").put("name", "one");
            result.put("nextCursor", "resources-2");
        } else {
            result.putArray("resources").addObject().put("uri", "fixture:///two").put("name", "two");
        }
    }

    private static void callTool(JsonNode request, ObjectNode response) {
        ObjectNode result = response.putObject("result");
        String name = request.path("params").path("name").asText();
        String value = request.path("params").path("arguments").path("value").asText("");
        result.putArray("content").addObject().put("type", "text").put("text", name + ":" + value);
        result.putObject("structuredContent").put("tool", name).put("value", value);
        result.put("isError", false);
    }
}
