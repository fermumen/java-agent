package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpProtocolParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void canonicalJsonRpcErrorsDoNotPoisonTheConnection() throws Exception {
        FakeBackend backend = new FakeBackend();
        List<JsonNode> output = exchange(backend,
                "this is not json",
                request(1, "session/new", "{\"mcpServers\":[]}"),
                request(2, "initialize", "{\"protocolVersion\":\"1\"}"),
                request(3, "initialize", "{\"protocolVersion\":1}"),
                request(4, "nonexistent/method", "{}"));

        assertError(output.get(0), -32700, "Parse error");
        assertError(output.get(1), -32600, "Not initialized");
        assertError(output.get(2), -32602, "Invalid initialize params");
        assertEquals(1, output.get(3).path("result").path("protocolVersion").asInt());
        assertError(output.get(4), -32601, "Method not found");
    }

    @Test
    void initializeReportsFxAcpCapabilityShapeWithoutImageSupport() throws Exception {
        JsonNode result = exchange(new FakeBackend(),
                request("init", "initialize", "{\"protocolVersion\":1}"))
                .get(0).path("result");

        assertEquals(1, result.path("protocolVersion").asInt());
        assertTrue(result.path("agentCapabilities").path("loadSession").asBoolean());
        assertFalse(result.path("agentCapabilities").path("promptCapabilities").path("image").asBoolean());
        assertFalse(result.path("agentCapabilities").path("promptCapabilities").path("audio").asBoolean());
        assertTrue(result.path("agentCapabilities").path("promptCapabilities")
                .path("embeddedContext").asBoolean());
        assertTrue(result.path("agentCapabilities").path("sessionCapabilities").has("list"));
        assertTrue(result.path("agentCapabilities").path("sessionCapabilities").has("resume"));
        assertTrue(result.path("agentCapabilities").path("sessionCapabilities").has("close"));
        assertEquals("java-agent", result.path("agentInfo").path("name").asText());
        assertTrue(result.path("authMethods").isArray());
    }

    @Test
    void sessionsExposeConfigModesListLoadResumeAndClose() throws Exception {
        FakeBackend backend = new FakeBackend();
        List<JsonNode> output = exchange(backend,
                request(1, "initialize", "{\"protocolVersion\":1}"),
                request(2, "session/new", "{\"mcpServers\":[]}"),
                request(3, "session/set_mode", "{\"modeId\":\"code\"}"),
                request(4, "session/set_config_option", "{\"configId\":\"model\",\"value\":\"model-2\"}"),
                request(5, "session/list", "{}"),
                request(6, "session/load", "{\"sessionId\":\"session-1\",\"mcpServers\":[]}"),
                request(7, "session/resume", "{\"sessionId\":\"session-1\",\"mcpServers\":[]}"),
                request(8, "session/close", "{\"sessionId\":\"session-1\"}"));

        JsonNode created = response(output, 2).path("result");
        assertEquals("session-1", created.path("sessionId").asText());
        assertEquals("ask", created.path("modes").path("currentModeId").asText());
        assertEquals("model-1", config(created, "model").path("currentValue").asText());
        assertEquals(List.of("code", "ask"), values(config(created, "mode")));

        JsonNode update = output.stream().filter(value -> value.path("method").asText().equals("session/update"))
                .findFirst().orElseThrow();
        assertEquals("available_commands_update", update.path("params").path("update")
                .path("sessionUpdate").asText());
        assertTrue(update.path("params").path("update").path("availableCommands").isArray());

        assertTrue(response(output, 3).path("result").isNull());
        assertEquals("model-2", config(response(output, 4).path("result"), "model")
                .path("currentValue").asText());
        JsonNode sessions = response(output, 5).path("result").path("sessions");
        assertEquals(1, sessions.size());
        assertEquals("session-1", sessions.path(0).path("sessionId").asText());
        assertEquals("ask", response(output, 6).path("result").path("modes")
                .path("currentModeId").asText());
        assertNotNull(response(output, 7).path("result").get("configOptions"));
        assertTrue(response(output, 8).path("result").isObject());
    }

    @Test
    void promptStreamsAgentChunksAndReturnsStopReason() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LiveClient client = new LiveClient(new AcpServer(json, backend))) {
            client.send(request(1, "initialize", "{\"protocolVersion\":1}"));
            assertEquals(1, client.read().path("id").asInt());
            client.send(request(2, "session/new", "{\"mcpServers\":[]}"));
            assertEquals(2, client.read().path("id").asInt());
            assertEquals("session/update", client.read().path("method").asText());

            client.send(request(3, "session/prompt",
                    "{\"sessionId\":\"session-1\",\"prompt\":[{\"type\":\"text\",\"text\":\"ping\"}]}"));
            JsonNode chunk = client.read();
            assertEquals("session/update", chunk.path("method").asText());
            assertEquals("agent_message_chunk", chunk.path("params").path("update")
                    .path("sessionUpdate").asText());
            assertEquals("pong", chunk.path("params").path("update").path("content").path("text").asText());
            JsonNode completed = client.read();
            assertEquals(3, completed.path("id").asInt());
            assertEquals("end_turn", completed.path("result").path("stopReason").asText());
            assertEquals(List.of("ping"), backend.prompts);
        }
    }

    @Test
    void imagePromptIsRejectedWithoutMutatingConversationAndNextTextPromptWorks() throws Exception {
        FakeBackend backend = new FakeBackend();
        try (LiveClient client = new LiveClient(new AcpServer(json, backend))) {
            client.send(request(1, "initialize", "{\"protocolVersion\":1}")); client.read();
            client.send(request(2, "session/new", "{\"mcpServers\":[]}")); client.read(); client.read();
            client.send(request(3, "session/prompt",
                    "{\"prompt\":[{\"type\":\"image\",\"data\":\"AA==\",\"mimeType\":\"image/png\"}]}"));
            assertError(client.read(), -32602, "image");
            assertTrue(backend.prompts.isEmpty());

            client.send(request(4, "session/prompt", "{\"prompt\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));
            client.read();
            assertEquals("end_turn", client.read().path("result").path("stopReason").asText());
            assertEquals(List.of("ok"), backend.prompts);
        }
    }

    private List<JsonNode> exchange(AcpServer.Backend backend, String... frames) throws Exception {
        String input = String.join("\n", frames) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new AcpServer(json, backend).serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
        List<JsonNode> values = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) values.add(json.readTree(line));
        }
        return values;
    }

    private String request(Object id, String method, String params) {
        String encodedId = id instanceof Number ? id.toString() : "\"" + id + "\"";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + encodedId + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private JsonNode response(List<JsonNode> values, int id) {
        return values.stream().filter(value -> value.path("id").asInt(-1) == id).findFirst().orElseThrow();
    }

    private JsonNode config(JsonNode result, String id) {
        for (JsonNode option : result.path("configOptions")) if (option.path("id").asText().equals(id)) return option;
        throw new AssertionError("Missing config option " + id);
    }

    private List<String> values(JsonNode option) {
        List<String> result = new ArrayList<>();
        option.path("options").forEach(value -> result.add(value.path("value").asText()));
        return result;
    }

    private void assertError(JsonNode response, int code, String message) {
        assertEquals(code, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().toLowerCase()
                .contains(message.toLowerCase()));
    }

    private static final class FakeBackend implements AcpServer.Backend {
        final Map<String, AcpServer.SessionSummary> sessions = new LinkedHashMap<>();
        final List<String> prompts = new ArrayList<>();
        String active;
        String model = "model-1";
        String mode = "ask";

        @Override public String newSession(List<JsonNode> mcpServers) {
            active = "session-" + (sessions.size() + 1);
            sessions.put(active, new AcpServer.SessionSummary(active, "C:\\workspace", Instant.parse("2026-08-19T00:00:00Z")));
            model = "model-1";
            mode = "ask";
            return active;
        }
        @Override public void loadSession(String id, List<JsonNode> mcpServers) {
            if (!sessions.containsKey(id)) throw new IllegalArgumentException("Session not found");
            active = id;
            mode = "ask";
        }
        @Override public void resumeSession(String id, List<JsonNode> mcpServers) { loadSession(id, mcpServers); }
        @Override public void closeSession(String id) {
            if (!id.equals(active)) throw new IllegalArgumentException("Session is not active");
            active = null;
        }
        @Override public List<AcpServer.SessionSummary> listSessions() { return List.copyOf(sessions.values()); }
        @Override public String activeSessionId() { return active; }
        @Override public String currentModel() { return model; }
        @Override public String currentMode() { return mode; }
        @Override public void setModel(String value) { model = value; }
        @Override public void setMode(String value) { mode = value; }
        @Override public String prompt(String prompt, Consumer<String> delta) {
            prompts.add(prompt);
            delta.accept("pong");
            return "pong";
        }
        @Override public void cancel() { }
        @Override public void close() { }
    }

    private final class LiveClient implements AutoCloseable {
        private final PipedOutputStream request;
        private final PipedInputStream serverInput;
        private final PipedOutputStream serverOutput;
        private final PipedInputStream response;
        private final BufferedReader reader;
        private final Thread thread;

        LiveClient(AcpServer server) throws Exception {
            request = new PipedOutputStream();
            serverInput = new PipedInputStream(request, 64 * 1024);
            serverOutput = new PipedOutputStream();
            response = new PipedInputStream(serverOutput, 64 * 1024);
            reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8));
            thread = new Thread(() -> {
                try { server.serve(serverInput, serverOutput); } catch (Exception ignored) { }
            }, "acp-test-server");
            thread.start();
        }

        void send(String frame) throws Exception {
            request.write((frame + "\n").getBytes(StandardCharsets.UTF_8));
            request.flush();
        }

        JsonNode read() throws Exception {
            String line = reader.readLine();
            assertNotNull(line);
            return json.readTree(line);
        }

        @Override public void close() throws Exception {
            request.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(thread.isAlive());
            response.close();
        }
    }
}
