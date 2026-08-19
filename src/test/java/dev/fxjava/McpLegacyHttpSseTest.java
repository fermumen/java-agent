package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpLegacyHttpSseTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void discoversSameOriginEndpointAndRoutesRequestsOverTheOpenEventStream() throws Exception {
        try (Fixture fixture = new Fixture("\n", "/messages", "2024-11-05")) {
            try (McpRuntime runtime = load(fixture, "legacy.json")) {
                Tool tool = runtime.tools().stream()
                        .filter(candidate -> candidate.name().equals("mcp__legacy__echo"))
                        .findFirst().orElseThrow();
                runtime.selectTool(tool.name());
                assertTrue(tool.execute(json.createObjectNode().put("text", "hello"))
                        .contains("legacy:hello"));
            }

            assertEquals(1, fixture.discoveryGets.get());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                    fixture.methods);
            assertEquals("2024-11-05", fixture.requestedProtocol);
            assertEquals(0, fixture.deletes.get());
        }
    }

    @Test
    void acceptsBareCarriageReturnEventDelimiters() throws Exception {
        try (Fixture fixture = new Fixture("\r", "/messages", "2024-11-05");
             McpRuntime runtime = load(fixture, "bare-cr.json")) {
            assertTrue(runtime.tools().stream().anyMatch(tool -> tool.name().equals("mcp__legacy__echo")));
        }
    }

    @Test
    void rejectsCrossOriginDiscoveredMessageEndpoint() throws Exception {
        try (Fixture fixture = new Fixture("\n", "http://localhost:%PORT%/messages", "2024-11-05")) {
            IOException failure = assertThrows(IOException.class, () -> load(fixture, "cross-origin.json"));
            assertTrue(failure.getMessage().contains("same-origin")
                    || failure.getMessage().contains("discovery origin"));
            assertEquals(0, fixture.methods.size());
        }
    }

    @Test
    void requiresTheDeprecatedTransportProtocolVersion() throws Exception {
        try (Fixture fixture = new Fixture("\n", "/messages", "2025-06-18")) {
            IOException failure = assertThrows(IOException.class, () -> load(fixture, "bad-version.json"));
            assertTrue(failure.getMessage().contains("2024-11-05"));
        }
    }

    private McpRuntime load(Fixture fixture, String file) throws IOException {
        Path config = temporary.resolve(file);
        Files.writeString(config, "{\"mcp\":{\"legacy\":{\"type\":\"sse\",\"url\":\""
                + fixture.url() + "\",\"required\":true,\"headers\":{\"X-Workspace\":\"legacy\"}}}}",
                StandardCharsets.UTF_8);
        return McpRuntime.load(json, config);
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final CountDownLatch stop = new CountDownLatch(1);
        private final Object streamLock = new Object();
        private final String delimiter;
        private final String endpointEvent;
        private final String protocolVersion;
        private final AtomicInteger discoveryGets = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private volatile OutputStream stream;
        private volatile String requestedProtocol;

        Fixture(String delimiter, String endpointEvent, String protocolVersion) throws IOException {
            this.delimiter = delimiter;
            this.endpointEvent = endpointEvent;
            this.protocolVersion = protocolVersion;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/sse", this::discover);
            server.createContext("/messages", this::message);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
        }

        private void discover(HttpExchange exchange) throws IOException {
            discoveryGets.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("text/event-stream", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals("legacy", exchange.getRequestHeaders().getFirst("X-Workspace"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            stream = exchange.getResponseBody();
            String endpoint = endpointEvent.replace("%PORT%", Integer.toString(server.getAddress().getPort()));
            emit("event: endpoint" + delimiter + "data: " + endpoint + delimiter + delimiter);
            try {
                stop.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        private void message(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("DELETE")) {
                deletes.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("legacy", exchange.getRequestHeaders().getFirst("X-Workspace"));
            assertEquals(null, exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            if (method.equals("initialize")) {
                requestedProtocol = request.path("params").path("protocolVersion").asText();
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            if (!request.has("id")) return;

            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            switch (method) {
                case "initialize" -> response.putObject("result")
                        .put("protocolVersion", protocolVersion)
                        .putObject("capabilities").putObject("tools");
                case "tools/list" -> {
                    ObjectNode tool = response.putObject("result").putArray("tools").addObject();
                    tool.put("name", "echo").put("description", "Legacy echo");
                    tool.putObject("annotations").put("readOnlyHint", true);
                    ObjectNode schema = tool.putObject("inputSchema").put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                }
                case "tools/call" -> response.putObject("result").putArray("content").addObject()
                        .put("type", "text").put("text", "legacy:"
                                + request.path("params").path("arguments").path("text").asText());
                default -> throw new IOException("Unexpected request: " + method);
            }
            emit("event: message" + delimiter + "data: " + json.writeValueAsString(response)
                    + delimiter + delimiter);
        }

        private void emit(String value) throws IOException {
            synchronized (streamLock) {
                stream.write(value.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
        }

        @Override
        public void close() {
            stop.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
