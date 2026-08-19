package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpSessionRecoveryTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void reinitializesExpiredSessionWithoutReplayingAmbiguousToolCall() throws Exception {
        try (Fixture server = new Fixture()) {
            Path config = temporary.resolve("mcp.json");
            Files.writeString(config, "{\"mcp\":{\"remote\":{\"type\":\"http\",\"url\":\""
                    + server.url() + "\",\"required\":true}}}");
            try (McpRuntime runtime = McpRuntime.load(json, config)) {
                Tool remote = runtime.tools().stream()
                        .filter(tool -> tool.name().equals("mcp__remote__mutate")).findFirst().orElseThrow();
                runtime.tools().stream().filter(tool -> tool.name().equals("mcp_select_tool"))
                        .findFirst().orElseThrow().execute(json.createObjectNode().put("name", remote.name()));

                IOException ambiguous = assertThrows(IOException.class,
                        () -> remote.execute(json.createObjectNode().put("value", "once")));
                assertTrue(ambiguous.getMessage().contains("not replayed"));
                assertEquals(1, server.toolCalls.get());
                assertTrue(remote.execute(json.createObjectNode().put("value", "deliberate"))
                        .contains("accepted:deliberate"));
            }
            assertEquals(2, server.initializes.get());
            assertEquals(2, server.toolCalls.get());
            assertEquals("session-2", server.deletedSession.get());
        }
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger initializes = new AtomicInteger();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicReference<String> deletedSession = new AtomicReference<>();

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        private void handle(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("DELETE")) {
                deletedSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (method.equals("initialize")) {
                assertEquals(null, session);
                int generation = initializes.incrementAndGet();
                ObjectNode response = envelope(request);
                response.putObject("result").put("protocolVersion", "2025-06-18")
                        .putObject("capabilities").putObject("tools");
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-" + generation);
                send(exchange, response, 200);
                return;
            }
            assertEquals("session-" + initializes.get(), session);
            assertEquals("2025-06-18", exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if (method.equals("tools/list")) {
                ObjectNode response = envelope(request);
                ObjectNode tool = response.putObject("result").putArray("tools").addObject();
                tool.put("name", "mutate").put("description", "Potential mutation");
                tool.putObject("inputSchema").put("type", "object");
                send(exchange, response, 200);
                return;
            }
            if (method.equals("tools/call")) {
                int call = toolCalls.incrementAndGet();
                if (call == 1) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                ObjectNode response = envelope(request);
                String value = request.path("params").path("arguments").path("value").asText();
                response.putObject("result").putArray("content").addObject()
                        .put("type", "text").put("text", "accepted:" + value);
                send(exchange, response, 200);
                return;
            }
            throw new IOException("Unexpected method: " + method);
        }

        private ObjectNode envelope(JsonNode request) {
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            return response;
        }

        private void send(HttpExchange exchange, JsonNode body, int status) throws IOException {
            byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
