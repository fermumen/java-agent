package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpCancellationParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cancelRequestInterruptsPromptRespondsToBothAndKeepsServerUsable() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (Client client = new Client(backend)) {
            initializeSession(client);
            client.send(request(3, "session/prompt", "{\"prompt\":[{\"type\":\"text\",\"text\":\"wait\"}]}"));
            assertTrue(backend.started.await(2, TimeUnit.SECONDS));
            client.send(request(4, "session/cancel", "{}"));

            JsonNode first = client.read();
            JsonNode second = client.read();
            JsonNode prompt = first.path("id").asInt() == 3 ? first : second;
            JsonNode cancel = first.path("id").asInt() == 4 ? first : second;
            assertEquals("cancelled", prompt.path("result").path("stopReason").asText());
            assertTrue(cancel.path("result").isNull());
            assertEquals(1, backend.cancels);

            client.send(request(5, "session/list", "{}"));
            assertTrue(client.read().path("result").path("sessions").isArray());
        }
    }

    @Test
    void requestsThatRequireIdlePromptAreRejectedUntilCancellationSettles() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        try (Client client = new Client(backend)) {
            initializeSession(client);
            client.send(request(3, "session/prompt", "{\"prompt\":[{\"type\":\"text\",\"text\":\"wait\"}]}"));
            assertTrue(backend.started.await(2, TimeUnit.SECONDS));
            client.send(request(4, "session/list", "{}"));
            JsonNode busy = client.read();
            assertEquals(-32600, busy.path("error").path("code").asInt());
            assertTrue(busy.path("error").path("message").asText().contains("Prompt already in progress"));
            client.send(request(5, "session/cancel", "{}"));
            client.read();
            client.read();
        }
    }

    @Test
    void stdinEofInterruptsAndJoinsThePromptWorker() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        Client client = new Client(backend);
        initializeSession(client);
        client.send(request(3, "session/prompt", "{\"prompt\":[{\"type\":\"text\",\"text\":\"wait\"}]}"));
        assertTrue(backend.started.await(2, TimeUnit.SECONDS));
        client.closeInput();
        client.join();
        assertFalse(client.thread.isAlive());
        assertEquals(1, backend.cancels);
        client.closeOutput();
    }

    private void initializeSession(Client client) throws Exception {
        client.send(request(1, "initialize", "{\"protocolVersion\":1}")); client.read();
        client.send(request(2, "session/new", "{\"mcpServers\":[]}")); client.read(); client.read();
    }

    private String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private static final class BlockingBackend implements AcpServer.Backend {
        final CountDownLatch started = new CountDownLatch(1);
        volatile int cancels;
        volatile String active;
        @Override public String newSession(List<JsonNode> mcpServers) { active = "session"; return active; }
        @Override public void loadSession(String id, List<JsonNode> mcpServers) { active = id; }
        @Override public void resumeSession(String id, List<JsonNode> mcpServers) { active = id; }
        @Override public void closeSession(String id) { active = null; }
        @Override public List<AcpServer.SessionSummary> listSessions() {
            return List.of(new AcpServer.SessionSummary("session", "C:\\workspace", Instant.EPOCH));
        }
        @Override public String activeSessionId() { return active; }
        @Override public String currentModel() { return "model"; }
        @Override public String currentMode() { return "ask"; }
        @Override public void setModel(String value) { }
        @Override public void setMode(String value) { }
        @Override public String prompt(String prompt, Consumer<String> delta) throws Exception {
            started.countDown();
            Thread.sleep(30_000);
            return "late";
        }
        @Override public void cancel() { cancels++; }
        @Override public void close() { }
    }

    private final class Client implements AutoCloseable {
        final PipedOutputStream request = new PipedOutputStream();
        final PipedInputStream serverInput;
        final PipedOutputStream serverOutput = new PipedOutputStream();
        final PipedInputStream response;
        final BufferedReader reader;
        final Thread thread;

        Client(AcpServer.Backend backend) throws Exception {
            serverInput = new PipedInputStream(request, 64 * 1024);
            response = new PipedInputStream(serverOutput, 64 * 1024);
            reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8));
            AcpServer server = new AcpServer(json, backend);
            thread = new Thread(() -> {
                try { server.serve(serverInput, serverOutput); } catch (Exception ignored) { }
            }, "acp-cancellation-test");
            thread.start();
        }

        void send(String value) throws Exception {
            request.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            request.flush();
        }

        JsonNode read() throws Exception {
            String line = reader.readLine();
            assertNotNull(line);
            return json.readTree(line);
        }

        void closeInput() throws Exception { request.close(); }
        void join() throws Exception { thread.join(TimeUnit.SECONDS.toMillis(5)); }
        void closeOutput() throws Exception { response.close(); }

        @Override public void close() throws Exception {
            if (request != null) request.close();
            join();
            assertFalse(thread.isAlive());
            response.close();
        }
    }
}
