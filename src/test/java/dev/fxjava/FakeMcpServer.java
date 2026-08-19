package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Line-delimited stdio MCP fixture used by the runtime contract tests. */
public final class FakeMcpServer {
    private FakeMcpServer() { }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        for (String line; (line = input.readLine()) != null;) {
            JsonNode request = json.readTree(line);
            if (!request.has("id")) continue;
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            String method = request.path("method").asText();
            switch (method) {
                case "initialize" -> response.putObject("result")
                        .put("protocolVersion", "2025-06-18")
                        .putObject("capabilities").putObject("tools").put("listChanged", true);
                case "tools/list" -> listTools(request, response);
                case "tools/call" -> callTool(request, response);
                case "resources/list" -> listResources(request, response);
                case "resources/templates/list" -> response.putObject("result").putArray("resourceTemplates")
                        .addObject().put("uriTemplate", "fixture:///{name}").put("name", "fixture");
                case "resources/read" -> response.putObject("result").putArray("contents")
                        .addObject().put("uri", request.path("params").path("uri").asText()).put("text", "resource body");
                case "prompts/list" -> response.putObject("result").putArray("prompts")
                        .addObject().put("name", "review").put("description", "Review prompt");
                case "prompts/get" -> response.putObject("result").putArray("messages")
                        .addObject().put("role", "user").putObject("content").put("type", "text").put("text", "review it");
                case "completion/complete" -> response.putObject("result").putObject("completion")
                        .putArray("values").add("alpha").add("beta");
                default -> response.putObject("error").put("code", -32601).put("message", "unknown");
            }
            output.println(json.writeValueAsString(response));
        }
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
