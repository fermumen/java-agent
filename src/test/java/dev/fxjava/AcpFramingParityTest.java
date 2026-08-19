package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpFramingParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactAndOversizedFramesPreserveTheConnectionBoundary() throws Exception {
        String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"padding\":\"";
        String suffix = "\"}}";
        int padding = AcpServer.MAX_FRAME_BYTES
                - prefix.getBytes(StandardCharsets.UTF_8).length - suffix.getBytes(StandardCharsets.UTF_8).length;
        String exact = prefix + "a".repeat(padding) + suffix;
        String oversized = exact + "x";
        String following = request(2, "unknown/method", "{}") + "\n";

        List<JsonNode> output = exchange(exact + "\n" + oversized + "\n" + following);
        assertEquals(3, output.size());
        assertEquals(1, output.get(0).path("result").path("protocolVersion").asInt());
        assertEquals(-32000, output.get(1).path("error").path("code").asInt());
        assertEquals(-32601, output.get(2).path("error").path("code").asInt());
        assertEquals(2, output.get(2).path("id").asInt());
    }

    @Test
    void notificationsWithoutIdsNeverBecomeResponseTargets() throws Exception {
        NoopBackend backend = new NoopBackend();
        String input = request(1, "initialize", "{\"protocolVersion\":1}") + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"unknown/notification\",\"params\":{}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{}}\n"
                + request(2, "session/list", "{}") + "\n";
        List<JsonNode> output = exchange(backend, input);

        assertEquals(2, output.size());
        assertEquals(1, output.get(0).path("id").asInt());
        assertEquals(2, output.get(1).path("id").asInt());
        assertEquals(0, backend.cancels);
    }

    @Test
    void unterminatedFrameAtEofIsDiscarded() throws Exception {
        List<JsonNode> output = exchange(request("init", "initialize", "{\"protocolVersion\":1}"));
        assertTrue(output.isEmpty());
    }

    @Test
    void backendAuthenticationFailureIsAnInitializeErrorAndWritesNoSessionState() throws Exception {
        NoopBackend backend = new NoopBackend() {
            @Override public void initialize() { throw new IllegalStateException("An OpenAI API key is required"); }
        };
        List<JsonNode> output = exchange(backend, request(1, "initialize", "{\"protocolVersion\":1}") + "\n");
        assertEquals(-32600, output.get(0).path("error").path("code").asInt());
        assertTrue(output.get(0).path("error").path("message").asText().contains("API key"));
        assertFalse(backend.created);
    }

    private List<JsonNode> exchange(String input) throws Exception { return exchange(new NoopBackend(), input); }

    private List<JsonNode> exchange(AcpServer.Backend backend, String input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new AcpServer(json, backend).serve(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        List<JsonNode> values = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) values.add(json.readTree(line));
        }
        return values;
    }

    private String request(Object id, String method, String params) {
        String encoded = id instanceof Number ? id.toString() : "\"" + id + "\"";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + encoded + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private static class NoopBackend implements AcpServer.Backend {
        int cancels;
        boolean created;
        @Override public String newSession(List<JsonNode> mcpServers) { created = true; return "session"; }
        @Override public void loadSession(String id, List<JsonNode> mcpServers) { }
        @Override public void resumeSession(String id, List<JsonNode> mcpServers) { }
        @Override public void closeSession(String id) { }
        @Override public List<AcpServer.SessionSummary> listSessions() { return List.of(); }
        @Override public String activeSessionId() { return null; }
        @Override public String currentModel() { return "model"; }
        @Override public String currentMode() { return "ask"; }
        @Override public void setModel(String value) { }
        @Override public void setMode(String value) { }
        @Override public String prompt(String prompt, Consumer<String> delta) { return ""; }
        @Override public void cancel() { cancels++; }
        @Override public void close() { }
    }
}
