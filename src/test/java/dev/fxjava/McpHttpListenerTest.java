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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpListenerTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void refreshesToolsFromTheGetListenerWithoutReinitializing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path config = temporary.resolve("listener.json");
            Files.writeString(config, "{\"mcp\":{\"remote\":{\"type\":\"http\",\"url\":\""
                    + fixture.url() + "\",\"required\":true,\"headers\":{\"X-Workspace\":\"listener\"}}}}",
                    StandardCharsets.UTF_8);

            try (McpRuntime runtime = McpRuntime.load(json, config)) {
                assertTrue(fixture.changedSent.await(2, TimeUnit.SECONDS));
                Tool search = named(runtime, "mcp_search_tools");
                String found = "";
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!found.contains("mcp__remote__fresh") && System.nanoTime() < deadline) {
                    found = search.execute(json.createObjectNode().put("query", "fresh"));
                    if (!found.contains("mcp__remote__fresh")) Thread.sleep(10);
                }
                assertTrue(found.contains("mcp__remote__fresh"));

                Tool select = named(runtime, "mcp_select_tool");
                select.execute(json.createObjectNode().put("name", "mcp__remote__fresh"));
                Tool fresh = ((DynamicToolProvider) select).resolveDynamicTool("mcp__remote__fresh");
                assertNotNull(fresh);
                assertTrue(fresh.advertised());
                assertTrue(fresh.execute(json.createObjectNode().put("text", "changed"))
                        .contains("fresh:changed"));
            }

            assertEquals(1, fixture.initializeCalls.get());
            assertEquals(2, fixture.toolsListCalls.get());
            assertEquals(1, fixture.listenerGets.get());
            assertEquals(1, fixture.deletes.get());
            assertEquals("session-listener", fixture.listenerSession);
            assertEquals("2025-06-18", fixture.listenerVersion);
        }
    }

    private static Tool named(McpRuntime runtime, String name) {
        return runtime.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final CountDownLatch firstList = new CountDownLatch(1);
        private final CountDownLatch changedSent = new CountDownLatch(1);
        private final CountDownLatch stop = new CountDownLatch(1);
        private final AtomicInteger initializeCalls = new AtomicInteger();
        private final AtomicInteger toolsListCalls = new AtomicInteger();
        private final AtomicInteger listenerGets = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private volatile String currentTool = "echo";
        private volatile String listenerSession;
        private volatile String listenerVersion;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        private void handle(HttpExchange exchange) throws IOException {
            switch (exchange.getRequestMethod()) {
                case "GET":
                    listen(exchange);
                    break;
                case "DELETE":
                    delete(exchange);
                    break;
                case "POST":
                    post(exchange);
                    break;
                default:
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    break;
            }
        }

        private void listen(HttpExchange exchange) throws IOException {
            listenerGets.incrementAndGet();
            assertEquals("text/event-stream", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals("listener", exchange.getRequestHeaders().getFirst("X-Workspace"));
            listenerSession = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            listenerVersion = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream stream = exchange.getResponseBody()) {
                if (!firstList.await(2, TimeUnit.SECONDS)) return;
                currentTool = "fresh";
                ObjectNode notification = json.createObjectNode().put("jsonrpc", "2.0")
                        .put("method", "notifications/tools/list_changed");
                notification.putObject("params");
                stream.write(("event: message\r\ndata: " + json.writeValueAsString(notification)
                        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                stream.flush();
                changedSent.countDown();
                stop.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        private void delete(HttpExchange exchange) throws IOException {
            deletes.incrementAndGet();
            assertEquals("session-listener", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }

        private void post(HttpExchange exchange) throws IOException {
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            switch (method) {
                case "initialize":
                    initializeCalls.incrementAndGet();
                    response.putObject("result").put("protocolVersion", "2025-06-18")
                            .putObject("capabilities").putObject("tools").put("listChanged", true);
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "session-listener");
                    break;
                case "tools/list": {
                    int call = toolsListCalls.incrementAndGet();
                    ObjectNode tool = response.putObject("result").putArray("tools").addObject();
                    tool.put("name", currentTool).put("description", "Changing tool");
                    tool.putObject("annotations").put("readOnlyHint", true);
                    tool.putObject("inputSchema").put("type", "object");
                    if (call == 1) firstList.countDown();
                    break;
                }
                case "tools/call":
                    response.putObject("result").putArray("content").addObject()
                            .put("type", "text").put("text", request.path("params").path("name").asText()
                                    + ":" + request.path("params").path("arguments").path("text").asText());
                    break;
                default:
                    throw new IOException("Unexpected method: " + method);
            }
            byte[] body = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            stop.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
