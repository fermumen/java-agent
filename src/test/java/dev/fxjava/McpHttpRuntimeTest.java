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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void streamableHttpCarriesSessionVersionHeadersSupportsJsonAndSseAndDeletesSession() throws Exception {
        try (Fixture server = new Fixture()) {
            Path config = temporary.resolve("mcp.json");
            Files.writeString(config, "{\"mcp\":{\"remote\":{\"type\":\"http\",\"url\":\""
                    + server.url() + "\",\"required\":true,\"headers\":{\"X-Workspace\":\"one\"}}}}");
            try (McpRuntime runtime = McpRuntime.load(json, config)) {
                Tool remote = runtime.tools().stream()
                        .filter(tool -> tool.name().equals("mcp__remote__remote_echo")).findFirst().orElseThrow();
                assertFalse(remote.advertised());
                runtime.tools().stream().filter(tool -> tool.name().equals("mcp_select_tool"))
                        .findFirst().orElseThrow().execute(json.createObjectNode().put("name", remote.name()));
                assertTrue(remote.advertised());
                assertTrue(remote.execute(json.createObjectNode().put("value", "hello")).contains("remote:hello"));
            }
            assertTrue(server.deleted.get());
            assertEquals(4, server.posts.get());
        }
    }


    @Test
    void matchingMixedDelimiterSseCompletesWithoutWaitingForEof() throws Exception {
        try (Fixture server = new Fixture(true)) {
            Path config = temporary.resolve("held-open.json");
            Files.writeString(config, "{\"mcp\":{\"remote\":{\"type\":\"http\",\"url\":\""
                    + server.url() + "\",\"required\":true,\"headers\":{\"X-Workspace\":\"one\"}}}}");
            try (McpRuntime runtime = McpRuntime.load(json, config)) {
                Tool remote = runtime.tools().stream()
                        .filter(tool -> tool.name().equals("mcp__remote__remote_echo")).findFirst().orElseThrow();
                runtime.tools().stream().filter(tool -> tool.name().equals("mcp_select_tool"))
                        .findFirst().orElseThrow().execute(json.createObjectNode().put("name", remote.name()));
                var executor = Executors.newSingleThreadExecutor();
                try {
                    var result = executor.submit(() -> remote.execute(json.createObjectNode().put("value", "held")));
                    assertTrue(result.get(1, TimeUnit.SECONDS).contains("remote:held"));
                } finally {
                    server.release.countDown();
                    executor.shutdownNow();
                }
            }
        }
    }

    @Test
    void omittedSessionStillCarriesProtocolVersionAndSkipsDelete() throws Exception {
        try (Fixture server = new Fixture(false, false)) {
            Path config = temporary.resolve("no-session.json");
            Files.writeString(config, "{\"mcp\":{\"remote\":{\"type\":\"http\",\"url\":\""
                    + server.url() + "\",\"required\":true,\"headers\":{\"X-Workspace\":\"one\"}}}}");
            try (McpRuntime runtime = McpRuntime.load(json, config)) {
                assertTrue(runtime.tools().stream().anyMatch(tool -> tool.name().equals("mcp__remote__remote_echo")));
            }
            assertEquals(0, server.deletes.get());
            assertEquals(3, server.posts.get());
        }
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger posts = new AtomicInteger();
        private final AtomicBoolean deleted = new AtomicBoolean();
        private final AtomicInteger deletes = new AtomicInteger();
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean heldOpen;
        private final boolean issueSession;

        Fixture() throws IOException {
            this(false, true);
        }

        Fixture(boolean heldOpen) throws IOException {
            this(heldOpen, true);
        }

        Fixture(boolean heldOpen, boolean issueSession) throws IOException {
            this.heldOpen = heldOpen;
            this.issueSession = issueSession;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        private void handle(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if (exchange.getRequestMethod().equals("DELETE")) {
                deletes.incrementAndGet();
                deleted.set("session-1".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            posts.incrementAndGet();
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            assertEquals("application/json, text/event-stream", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals("one", exchange.getRequestHeaders().getFirst("X-Workspace"));
            if (!method.equals("initialize")) {
                assertEquals(issueSession ? "session-1" : null,
                        exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                assertEquals("2025-06-18", exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
            }
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            switch (method) {
                case "initialize" -> {
                    response.putObject("result").put("protocolVersion", "2025-06-18")
                            .putObject("capabilities").putObject("tools");
                    if (issueSession) exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                    send(exchange, json.writeValueAsString(response), "application/json");
                }
                case "tools/list" -> {
                    ObjectNode tool = response.putObject("result").putArray("tools").addObject();
                    tool.put("name", "remote.echo").put("description", "Remote echo");
                    tool.putObject("annotations").put("readOnlyHint", true);
                    tool.putObject("inputSchema").put("type", "object");
                    send(exchange, "data: " + json.writeValueAsString(response) + "\n\n", "text/event-stream");
                }
                case "tools/call" -> {
                    String value = request.path("params").path("arguments").path("value").asText();
                    response.putObject("result").putArray("content").addObject()
                            .put("type", "text").put("text", "remote:" + value);
                    if (heldOpen) sendHeldOpen(exchange, response);
                    else send(exchange, json.writeValueAsString(response), "application/json; charset=utf-8");
                }
                default -> throw new IOException("Unexpected method: " + method);
            }
        }

        private void sendHeldOpen(HttpExchange exchange, JsonNode response) throws IOException {
            byte[] bytes = (": keepalive\rdata: " + json.writeValueAsString(response) + "\r\r")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes, 0, bytes.length / 2);
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write(bytes, bytes.length / 2, bytes.length - bytes.length / 2);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        }

        private void send(HttpExchange exchange, String body, String contentType) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
