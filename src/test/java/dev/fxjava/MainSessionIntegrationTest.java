package dev.fxjava;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainSessionIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void resumeLastReplaysPriorResponsesInput() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path sessions = temporary.resolve("state");
        List<String> requests = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        try (TextResponsesServer api = new TextResponsesServer(requests, sequence)) {
            assertEquals(0, run(api, workspace, sessions, "first question"));
            assertEquals(0, run(api, workspace, sessions, "--resume", "last", "second question"));
        }

        assertEquals(2, requests.size());
        assertTrue(requests.get(0).contains("first question"));
        assertTrue(requests.get(1).contains("first question"));
        assertTrue(requests.get(1).contains("answer-1"));
        assertTrue(requests.get(1).contains("second question"));
    }

    private int run(TextResponsesServer api, Path workspace, Path sessions, String... prompt) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(
                "--base-url", api.baseUrl(),
                "--workspace", workspace.toString(),
                "--session-root", sessions.toString()));
        arguments.addAll(List.of(prompt));
        return Main.run(arguments.toArray(String[]::new), Map.of("OPENAI_API_KEY", "test-key"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
    }

    private static final class TextResponsesServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> requests;
        private final AtomicInteger sequence;

        TextResponsesServer(List<String> requests, AtomicInteger sequence) throws IOException {
            this.requests = requests;
            this.sequence = sequence;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int number = sequence.incrementAndGet();
            String answer = "answer-" + number;
            String response = "{\"id\":\"resp_" + number
                    + "\",\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
                    + "\"text\":\"" + answer + "\"}]}]}";
            String events = "data: {\"type\":\"response.output_text.delta\",\"delta\":\""
                    + answer + "\"}\n\ndata: {\"type\":\"response.completed\",\"response\":"
                    + response + "}\n\n";
            byte[] body = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
