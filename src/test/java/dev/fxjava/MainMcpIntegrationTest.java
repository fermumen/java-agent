package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainMcpIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void responsesLoopExecutesDiscoveredMcpTool() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path mcp = writeMcpConfig();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (McpResponsesServer api = new McpResponsesServer()) {
            int code = Main.run(new String[]{
                            "--base-url", api.baseUrl(), "--workspace", workspace.toString(),
                            "--session-root", temporary.resolve("state").toString(),
                            "--mcp-config", mcp.toString(), "use the MCP echo"
                    }, Map.of("OPENAI_API_KEY", "test-key"), new PrintStream(output),
                    new PrintStream(new ByteArrayOutputStream()));
            assertEquals(0, code);
            assertEquals(3, api.requests.get());
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("MCP complete"));
    }

    private Path writeMcpConfig() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = json.createObjectNode();
        ArrayNode command = root.putObject("mcp").putObject("fixture").put("type", "stdio")
                .putArray("command");
        command.add(Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
        command.add("-cp").add(System.getProperty("java.class.path")).add(FakeMcpServer.class.getName());
        Path path = temporary.resolve("mcp.json");
        Files.writeString(path, json.writeValueAsString(root));
        return path;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static final class McpResponsesServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        McpResponsesServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void handle(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int number = requests.incrementAndGet();
            String completed;
            String prefix = "";
            if (number == 1) {
                if (!request.contains("mcp_select_tool") || request.contains("\"name\":\"mcp__fixture__zeta_echo\"")) {
                    send(exchange, 400, "dynamic MCP schema advertised before selection", "text/plain");
                    return;
                }
                completed = "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\","
                        + "\"call_id\":\"select-call\",\"name\":\"mcp_select_tool\","
                        + "\"arguments\":\"{\\\"name\\\":\\\"mcp__fixture__zeta_echo\\\"}\"}]}";
            } else if (number == 2) {
                if (!request.contains("Echo structured input") || !request.contains("advertised")) {
                    send(exchange, 400, "selected MCP schema was not advertised", "text/plain");
                    return;
                }
                completed = "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\","
                        + "\"call_id\":\"mcp-call\",\"name\":\"mcp__fixture__zeta_echo\","
                        + "\"arguments\":\"{\\\"value\\\":\\\"hello\\\"}\"}]}";
            } else {
                if (!request.contains("function_call_output") || !request.contains("zeta.echo:hello")
                        || !request.contains("structuredContent")) {
                    send(exchange, 400, "missing MCP result replay", "text/plain");
                    return;
                }
                prefix = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"MCP complete\"}\n\n";
                completed = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"MCP complete\"}]}]}";
            }
            String events = prefix + "data: {\"type\":\"response.completed\",\"response\":" + completed + "}\n\n";
            send(exchange, 200, events, "text/event-stream");
        }

        private static void send(HttpExchange exchange, int status, String body, String contentType)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
