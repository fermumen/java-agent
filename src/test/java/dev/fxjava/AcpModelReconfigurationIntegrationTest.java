package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpModelReconfigurationIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void modelPersistsAcrossLoadAndCodeModeRebuildsTheLiveAgent() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        List<JsonNode> requests = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/v1/responses", exchange -> respond(exchange, requests, sequence));
        api.start();
        String endpoint = "http://127.0.0.1:" + api.getAddress().getPort() + "/v1";
        String sessionId;
        try {
            try (AcpAgentBackend first = backend(workspace, state, endpoint)) {
                first.initialize();
                sessionId = first.newSession(List.of());
                first.setModel("model-2");
                first.setMode("code");
                assertEquals("answer-1", first.prompt("first", ignored -> { }));
            }

            try (AcpAgentBackend restored = backend(workspace, state, endpoint)) {
                restored.initialize();
                restored.loadSession(sessionId, List.of());
                assertEquals("model-2", restored.currentModel());
                assertEquals("ask", restored.currentMode());
                assertEquals("answer-2", restored.prompt("second", ignored -> { }));
                restored.setMode("code");
                assertEquals("answer-3", restored.prompt("third", ignored -> { }));
            }
        } finally {
            api.stop(0);
        }

        assertEquals(3, requests.size());
        assertEquals("model-2", requests.get(0).path("model").asText());
        assertTrue(requests.get(0).path("instructions").asText().contains("Permission mode: auto"));
        assertEquals("model-2", requests.get(1).path("model").asText());
        assertTrue(requests.get(1).path("input").toString().contains("first"));
        assertTrue(requests.get(1).path("input").toString().contains("answer-1"));
        assertTrue(requests.get(1).path("instructions").asText().contains("Permission mode: ask"));
        assertTrue(requests.get(2).path("instructions").asText().contains("Permission mode: auto"));
    }

    private AcpAgentBackend backend(Path workspace, Path state, String endpoint) throws Exception {
        return new AcpAgentBackend(json, "test-key", endpoint, "model-1", workspace, 20,
                PermissionMode.AUTO, state, state.resolve("mcp.json"), false);
    }

    private void respond(HttpExchange exchange, List<JsonNode> requests, AtomicInteger sequence) {
        try {
            synchronized (requests) { requests.add(json.readTree(exchange.getRequestBody())); }
            int number = sequence.incrementAndGet();
            String answer = "answer-" + number;
            String response = "{\"id\":\"resp-" + number
                    + "\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":\"" + answer + "\"}]}]}";
            byte[] body = ("data: {\"type\":\"response.completed\",\"response\":" + response + "}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (Exception ignored) { }
    }
}
