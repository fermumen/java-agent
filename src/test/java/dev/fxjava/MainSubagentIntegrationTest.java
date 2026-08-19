package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSubagentIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void executableChildEmitsMilestoneThenInspectWaitReturnsItsSettledAnswer() throws Exception {
        AtomicInteger parentRequests = new AtomicInteger();
        AtomicInteger childRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> handle(exchange, parentRequests, childRequests));
        server.start();
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int code = Main.run(new String[]{"--json", "--yolo", "--no-save", "--workspace",
                            workspace.toString(), "--base-url",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "delegate this"}, Map.of("OPENAI_API_KEY", "test-key"),
                    new PrintStream(stdout), new PrintStream(stderr));
            assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
            JsonNode result = json.readTree(stdout.toString(StandardCharsets.UTF_8));
            assertEquals("delegation complete", result.path("output").asText());
            assertEquals(2, result.path("tool_calls").size());
            assertEquals("subagent", result.path("tool_calls").path(0).path("name").asText());
            assertEquals(3, parentRequests.get());
            assertEquals(2, childRequests.get());
        } finally {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange, AtomicInteger parentRequests, AtomicInteger childRequests) {
        try {
            JsonNode request = json.readTree(exchange.getRequestBody());
            boolean child = request.path("instructions").asText().contains("Subagent identity:");
            String response;
            if (child) {
                int number = childRequests.incrementAndGet();
                if (number == 1) {
                    response = functionResponse("child-milestone", "subagent",
                            "{\"command\":{\"message\":{\"milestone\":{\"name\":\"halfway\"}}}}");
                } else {
                    assertTrue(latestToolOutput(request).contains("milestone_emitted"));
                    response = textResponse("child evidence");
                }
            } else {
                int number = parentRequests.incrementAndGet();
                if (number == 1) {
                    response = functionResponse("parent-create", "subagent",
                            "{\"command\":{\"create\":{\"name\":\"worker\",\"mode\":\"one_off\","
                                    + "\"prompt\":\"investigate\",\"notifications\":{\"milestones\":[\"halfway\"]}}}}");
                } else if (number == 2) {
                    String output = latestToolOutput(request);
                    String childId = json.readTree(output).path("child_id").asText();
                    assertTrue(!childId.isBlank());
                    ObjectNode arguments = json.createObjectNode();
                    ObjectNode inspect = arguments.putObject("command").putObject("inspect");
                    inspect.put("id", childId).putArray("sections").add("status").add("messages");
                    inspect.set("wait", json.createObjectNode().put("until", "settled").put("timeout_ms", 5_000));
                    response = functionResponse("parent-inspect", "subagent", arguments.toString());
                } else {
                    String output = latestToolOutput(request);
                    assertTrue(output.contains("child evidence"));
                    response = textResponse("delegation complete");
                }
            }
            byte[] body = ("data: {\"type\":\"response.completed\",\"response\":" + response + "}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (Exception failure) {
            try {
                byte[] body = failure.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            } catch (Exception ignored) { }
        }
    }

    private String latestToolOutput(JsonNode request) {
        JsonNode input = request.path("input");
        for (int index = input.size() - 1; index >= 0; index--) {
            if (input.path(index).path("type").asText().equals("function_call_output")) {
                return input.path(index).path("output").asText();
            }
        }
        throw new AssertionError("missing function_call_output");
    }

    private String functionResponse(String callId, String name, String arguments) throws Exception {
        ObjectNode response = json.createObjectNode().put("id", "resp-" + callId)
                .put("object", "response").put("status", "completed");
        response.putArray("output").addObject().put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments)
                .put("status", "completed");
        return json.writeValueAsString(response);
    }

    private String textResponse(String text) throws Exception {
        ObjectNode response = json.createObjectNode().put("id", "resp-text")
                .put("object", "response").put("status", "completed");
        response.putArray("output").addObject().put("type", "message").put("role", "assistant")
                .put("status", "completed").putArray("content").addObject()
                .put("type", "output_text").put("text", text).putArray("annotations");
        return json.writeValueAsString(response);
    }
}
