package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesOutputItemsExecutesToolAndReturnsFinalText() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-1", "echo", "{\"value\":\"hello\"}"),
                textResponse("Finished"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(progress, true, StandardCharsets.UTF_8), 5, "system");

        assertEquals("Finished", agent.prompt("Do it"));
        assertEquals(2, client.requests.size());
        ArrayNode secondInput = client.requests.get(1);
        assertEquals("user", secondInput.path(0).path("role").asText());
        assertEquals("reasoning", secondInput.path(1).path("type").asText());
        assertEquals("function_call", secondInput.path(2).path("type").asText());
        assertEquals("function_call_output", secondInput.path(3).path("type").asText());
        assertEquals("call-1", secondInput.path(3).path("call_id").asText());
        assertEquals("echo: hello", secondInput.path(3).path("output").asText());
        assertTrue(progress.toString(StandardCharsets.UTF_8).contains("[tool] echo hello"));
        assertEquals("system", client.instructions.get(0));
    }

    @Test
    void deniedToolCallIsReturnedAsFunctionCallOutput() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-2", "echo", "{\"value\":\"no\"}"),
                textResponse("Denied safely"));
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Denied safely", agent.prompt("Do it"));
        assertEquals("Error: user denied this tool call",
                client.requests.get(1).path(3).path("output").asText());
    }

    @Test
    void advertisesAndDispatchesToolsFromADynamicProvider() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-dynamic", "fresh", "{\"value\":\"now\"}"),
                textResponse("Refreshed"));
        Agent agent = new Agent(json, client, List.of(new ProviderTool()), (tool, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Refreshed", agent.prompt("Use fresh"));
        assertEquals("fresh", client.toolDefinitions.get(0).path(0).path("name").asText());
        assertEquals("echo: now", client.requests.get(1).path(3).path("output").asText());
    }

    @Test
    void hostedWebSearchUsesResponsesBuiltInDefinition() throws Exception {
        FakeClient client = new FakeClient(textResponse("Searched"));
        Agent agent = new Agent(json, client, List.of(new HostedWebSearchTool()), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 2, "system");

        assertEquals("Searched", agent.prompt("Find it"));
        JsonNode definition = client.toolDefinitions.get(0).path(0);
        assertEquals("web_search", definition.path("type").asText());
        assertTrue(!definition.has("name") && !definition.has("parameters"));
    }

    @Test
    void injectsEphemeralParentContextAndAcknowledgesOnlyAfterProviderSuccess() throws Exception {
        FakeClient client = new FakeClient(textResponse("Observed"));
        AtomicInteger acknowledgements = new AtomicInteger();
        Agent.ParentContext parent = new Agent.ParentContext() {
            public Agent.PreparedParentContext prepare() {
                return new Agent.PreparedParentContext("<subagent_deliveries>trusted</subagent_deliveries>",
                        List.of(new Agent.ParentDeliveryAck("child", "root", 4)));
            }
            public void acknowledge(Agent.PreparedParentContext prepared) {
                acknowledgements.incrementAndGet();
            }
        };
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 2, "system", null, parent);

        assertEquals("Observed", agent.prompt("continue"));
        assertEquals("system", client.requests.get(0).path(0).path("role").asText());
        assertTrue(client.requests.get(0).path(0).path("content").asText().contains("trusted"));
        assertEquals("user", client.requests.get(0).path(1).path("role").asText());
        assertEquals(1, acknowledgements.get());
        assertEquals(2, agent.snapshotInput().size());
        assertTrue(agent.snapshotInput().valueStream()
                .noneMatch(item -> item.path("role").asText().equals("system")));
    }

    private ObjectNode toolResponse(String callId, String name, String arguments) {
        ObjectNode response = completedResponse();
        ArrayNode output = response.putArray("output");
        output.addObject().put("id", "rs_1").put("type", "reasoning")
                .put("encrypted_content", "encrypted-state").putArray("summary");
        output.addObject().put("id", "fc_1").put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments)
                .put("status", "completed");
        return response;
    }

    private ObjectNode textResponse(String text) {
        ObjectNode response = completedResponse();
        ObjectNode message = response.putArray("output").addObject();
        message.put("id", "msg_1").put("type", "message").put("role", "assistant")
                .put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text").put("text", text)
                .putArray("annotations");
        return response;
    }

    private ObjectNode completedResponse() {
        return json.createObjectNode().put("id", "resp_test").put("object", "response")
                .put("status", "completed");
    }

    private static final class FakeClient implements ResponsesClient {
        private final Queue<ObjectNode> responses = new ArrayDeque<>();
        private final ArrayList<ArrayNode> requests = new ArrayList<>();
        private final ArrayList<ArrayNode> toolDefinitions = new ArrayList<>();
        private final ArrayList<String> instructions = new ArrayList<>();

        FakeClient(ObjectNode... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            requests.add(input.deepCopy());
            toolDefinitions.add(tools.deepCopy());
            this.instructions.add(instructions);
            return responses.remove();
        }
    }

    private final class ProviderTool implements Tool, DynamicToolProvider {
        private final Tool dynamic = new EchoTool() {
             public String name() { return "fresh"; }
        };

         public String name() { return "dynamic_provider"; }
         public boolean advertised() { return false; }
         public String description() { return "Dynamic tool provider"; }
         public ObjectNode parameters() { return json.createObjectNode().put("type", "object"); }
         public boolean requiresApproval() { return false; }
         public String preview(JsonNode arguments) { return "provider"; }
         public String execute(JsonNode arguments) { return "provider"; }
         public Tool resolveDynamicTool(String name) {
            return dynamic.name().equals(name) ? dynamic : null;
        }
         public List<Tool> dynamicTools() { return List.of(dynamic); }
    }

    private class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo a value"; }
        @Override public ObjectNode parameters() {
            ObjectNode schema = json.createObjectNode();
            schema.put("type", "object").putObject("properties")
                    .putObject("value").put("type", "string");
            return schema;
        }
        @Override public boolean requiresApproval() { return true; }
        @Override public String preview(JsonNode arguments) { return "echo " + arguments.path("value").asText(); }
        @Override public String execute(JsonNode arguments) { return "echo: " + arguments.path("value").asText(); }
    }
}
