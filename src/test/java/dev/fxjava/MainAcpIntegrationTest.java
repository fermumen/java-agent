package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainAcpIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void acpCommandStreamsNativeResponsesAndPersistsTheSession() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        AtomicReference<String> apiRequest = new AtomicReference<>();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/v1/responses", exchange -> respond(exchange, apiRequest));
        api.start();

        PipedOutputStream clientInput = new PipedOutputStream();
        PipedInputStream agentInput = new PipedInputStream(clientInput, 64 * 1024);
        PipedOutputStream agentOutput = new PipedOutputStream();
        PipedInputStream clientOutput = new PipedInputStream(agentOutput, 64 * 1024);
        BufferedReader responses = new BufferedReader(new InputStreamReader(clientOutput, StandardCharsets.UTF_8));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger exitCode = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread process = new Thread(() -> {
            try {
                exitCode.set(Main.run(new String[]{"--workspace", workspace.toString(), "--session-root",
                                state.toString(), "--base-url",
                                "http://127.0.0.1:" + api.getAddress().getPort() + "/v1", "acp"},
                        Map.of("OPENAI_API_KEY", "test-key"), agentInput,
                        new PrintStream(agentOutput, true, StandardCharsets.UTF_8),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8)));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "main-acp-integration");
        process.start();

        try {
            send(clientInput, 1, "initialize", "{\"protocolVersion\":1}");
            assertEquals(1, read(responses).path("id").asInt());
            send(clientInput, 2, "session/new", "{\"mcpServers\":[]}");
            JsonNode created = read(responses);
            String sessionId = created.path("result").path("sessionId").asText();
            assertFalse(sessionId.isBlank());
            assertEquals("session/update", read(responses).path("method").asText());

            send(clientInput, 3, "session/prompt", "{\"sessionId\":\"" + sessionId
                    + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"say pong\"}]}");
            JsonNode chunk = read(responses);
            assertEquals("pong", chunk.path("params").path("update").path("content").path("text").asText());
            JsonNode completed = read(responses);
            assertEquals(3, completed.path("id").asInt());
            assertEquals("end_turn", completed.path("result").path("stopReason").asText());

            clientInput.close();
            process.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(process.isAlive());
            assertEquals(null, failure.get());
            assertEquals(0, exitCode.get());
            assertEquals("", stderr.toString(StandardCharsets.UTF_8));
            assertTrue(apiRequest.get().contains("\"store\":false"));
            assertTrue(apiRequest.get().contains("say pong"));
            assertTrue(Files.isRegularFile(state.resolve("sessions").resolve(sessionId).resolve("session.json")));
        } finally {
            if (process.isAlive()) {
                clientInput.close();
                process.join(TimeUnit.SECONDS.toMillis(5));
            }
            api.stop(0);
            clientOutput.close();
        }
    }

    private void respond(HttpExchange exchange, AtomicReference<String> request) {
        try {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = "{\"id\":\"resp-acp\",\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"pong\"}]}]}";
            byte[] body = ("data: {\"type\":\"response.output_text.delta\",\"delta\":\"pong\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":" + response + "}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (Exception ignored) { }
    }

    private void send(PipedOutputStream input, int id, String method, String params) throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}\n";
        input.write(request.getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    private JsonNode read(BufferedReader input) throws Exception {
        return json.readTree(input.readLine());
    }
}
