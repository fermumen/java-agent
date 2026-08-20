package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay-safe native Responses subset ported from fx model response recovery. */
class OpenAiResponsesRetryTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void retryPolicyCoversOnlyRateLimitsAndTransientServerStatuses() {
        for (int status : new int[]{429, 500, 502, 503, 504}) {
            assertTrue(OpenAiResponsesClient.retryableStatus(status));
        }
        for (int status : new int[]{400, 401, 403, 409, 501, 505}) {
            assertTrue(!OpenAiResponsesClient.retryableStatus(status));
        }
        assertEquals(List.of(250L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L,
                        30_000L, 30_000L, 30_000L),
                java.util.stream.IntStream.rangeClosed(1, 9)
                        .mapToObj(attempt -> OpenAiResponsesClient.retryDelayMillis(null, attempt))
                        .collect(Collectors.toList()));
        assertEquals(2_000, OpenAiResponsesClient.retryDelayMillis("2", 1));
        assertEquals(30_000, OpenAiResponsesClient.retryDelayMillis("999999999999999999999", 1));
        assertEquals(1_000, OpenAiResponsesClient.retryDelayMillis("later", 2));
        assertEquals(0, OpenAiResponsesClient.retryDelayMillis(null, 0));

        OpenAiResponsesClient.RetryPacing pacing = new OpenAiResponsesClient.RetryPacing();
        assertEquals(250, pacing.next(429, null));
        assertEquals(1_000, pacing.next(429, null));
        assertEquals(250, pacing.next(503, null));
        assertEquals(0, pacing.next(503, "0"));
        assertEquals(250, pacing.next(503, null));
    }

    @Test
    void streamingRetriesPreOutputStatusAndHonorsRetryAfter() throws Exception {
        try (Fixture fixture = new Fixture(1, 503, "2", false)) {
            List<Long> sleeps = new ArrayList<>();
            OpenAiResponsesClient client = client(fixture, sleeps);
            List<String> deltas = new ArrayList<>();
            client.complete(json.createArrayNode(), json.createArrayNode(), "system", deltas::add);
            assertEquals(2, fixture.requests());
            assertEquals(List.of(2_000L), sleeps);
            assertEquals(List.of("ok"), deltas);
        }
    }

    @Test
    void jsonResponsesUseTheSameBoundedRetryPolicy() throws Exception {
        try (Fixture fixture = new Fixture(1, 429, "0", false)) {
            List<Long> sleeps = new ArrayList<>();
            OpenAiResponsesClient client = client(fixture, sleeps);
            assertEquals(0, client.complete(json.createArrayNode(), json.createArrayNode(), "system")
                    .path("output").size());
            assertEquals(2, fixture.requests());
            assertEquals(List.of(0L), sleeps);
        }
    }

    @Test
    void retryBudgetStopsAfterTenProviderAttempts() throws Exception {
        try (Fixture fixture = new Fixture(20, 503, null, false)) {
            List<Long> sleeps = new ArrayList<>();
            IOException failure = assertThrows(IOException.class, () -> client(fixture, sleeps)
                    .complete(json.createArrayNode(), json.createArrayNode(), "system", ignored -> { }));
            assertTrue(failure.getMessage().contains("HTTP 503"));
            assertEquals(OpenAiResponsesClient.MAX_PROVIDER_ATTEMPTS, fixture.requests());
            assertEquals(List.of(250L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L,
                    30_000L, 30_000L, 30_000L), sleeps);
        }
    }

    @Test
    void nonRetryableAndPartialSuccessfulStreamsAreNeverReplayed() throws Exception {
        try (Fixture badRequest = new Fixture(20, 400, null, false)) {
            assertThrows(IOException.class, () -> client(badRequest, new ArrayList<>())
                    .complete(json.createArrayNode(), json.createArrayNode(), "system", ignored -> { }));
            assertEquals(1, badRequest.requests());
        }
        try (Fixture truncated = new Fixture(0, 503, null, true)) {
            List<String> deltas = new ArrayList<>();
            assertThrows(IOException.class, () -> client(truncated, new ArrayList<>())
                    .complete(json.createArrayNode(), json.createArrayNode(), "system", deltas::add));
            assertEquals(List.of("partial"), deltas);
            assertEquals(1, truncated.requests());
        }
    }

    private OpenAiResponsesClient client(Fixture fixture, List<Long> sleeps) {
        AgentConfig config = new AgentConfig("test-key", fixture.baseUrl(), "model", workspace, 2,
                PermissionMode.ASK);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new OpenAiResponsesClient(json, http, config, sleeps::add);
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final int failures;
        private final int failureStatus;
        private final String retryAfter;
        private final boolean truncated;

        Fixture(int failures, int failureStatus, String retryAfter, boolean truncated) throws IOException {
            this.failures = failures;
            this.failureStatus = failureStatus;
            this.retryAfter = retryAfter;
            this.truncated = truncated;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int requests() { return requests.get(); }

        private void handle(HttpExchange exchange) throws IOException {
            int request = requests.incrementAndGet();
            if (request <= failures) {
                if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
                send(exchange, failureStatus, "try later");
                return;
            }
            boolean streaming = exchange.getRequestHeaders().getFirst("Accept").contains("text/event-stream");
            if (!streaming) {
                send(exchange, 200, "{\"status\":\"completed\",\"output\":[]}");
            } else if (truncated) {
                send(exchange, 200, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n");
            } else {
                send(exchange, 200, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                        + "\"output\":[]}}\n\n");
            }
        }

        private static void send(HttpExchange exchange, int status, String value) throws IOException {
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
