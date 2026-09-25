package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loopback-only tests for the cancellable Responses SSE body reader. */
class OpenAiResponsesStreamingTest {
    private static final String COMPLETED = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void completedEventReturnsAndClosesWhileServerKeepsConnectionOpen() throws Exception {
        CountDownLatch sentCompleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StreamServer server = new StreamServer(exchange -> {
            beginChunked(exchange);
            write(exchange, COMPLETED);
            sentCompleted.countDown();
            awaitRelease(release);
        });
        try {
            long started = System.nanoTime();
            ObjectNode response = client(server, Duration.ofSeconds(2), Duration.ofSeconds(5))
                    .complete(json.createArrayNode(), json.createArrayNode(), "system", ignored -> { });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(sentCompleted.await(1, TimeUnit.SECONDS));
            assertEquals("completed", response.path("status").asText());
            assertTrue(elapsedMillis < 1_500, "client waited for an open response body: " + elapsedMillis + "ms");
            assertEquals(1, server.requests());
        } finally {
            release.countDown();
            server.close();
        }
    }

    @Test
    void idleBodyTimesOutWithoutReplayingEmittedText() throws Exception {
        CountDownLatch sentDelta = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StreamServer server = new StreamServer(exchange -> {
            beginChunked(exchange);
            write(exchange, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n");
            sentDelta.countDown();
            awaitRelease(release);
        });
        try {
            List<String> deltas = new ArrayList<>();
            IOException failure = assertThrows(IOException.class, () -> client(server,
                    Duration.ofMillis(250), Duration.ofSeconds(3)).complete(
                    json.createArrayNode(), json.createArrayNode(), "system", deltas::add));
            assertTrue(sentDelta.await(1, TimeUnit.SECONDS));
            assertTrue(failure.getMessage().contains("idle timeout"), failure.toString());
            assertEquals(List.of("partial"), deltas);
            assertEquals(1, server.requests());
        } finally {
            release.countDown();
            server.close();
        }
    }

    @Test
    void interruptCancelsAnIdleBodyAndPreservesTheWorkerInterruptFlag() throws Exception {
        CountDownLatch sentDelta = new CountDownLatch(1);
        CountDownLatch deltaObserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StreamServer server = new StreamServer(exchange -> {
            beginChunked(exchange);
            write(exchange, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n");
            sentDelta.countDown();
            awaitRelease(release);
        });
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Boolean> interruptPreserved = new AtomicReference<>(false);
            Thread worker = new Thread(() -> {
                try {
                    client(server, Duration.ofSeconds(5), Duration.ofSeconds(10)).complete(
                            json.createArrayNode(), json.createArrayNode(), "system", ignored -> deltaObserved.countDown());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                }
            }, "openai-sse-interrupt-test");
            worker.start();
            assertTrue(sentDelta.await(2, TimeUnit.SECONDS));
            assertTrue(deltaObserved.await(2, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(2_000);
            assertFalse(worker.isAlive(), "interrupted SSE reader did not stop");
            assertInstanceOf(InterruptedException.class, failure.get());
            assertTrue(interruptPreserved.get(), "stream cancellation cleared the worker interrupt flag");
            assertEquals(1, server.requests());
        } finally {
            release.countDown();
            server.close();
        }
    }

    @Test
    void malformedAndOversizedEventsFailWithinConfiguredBounds() throws Exception {
        try (StreamServer malformed = new StreamServer(exchange -> {
            beginChunked(exchange);
            write(exchange, "data: {bad json}\n\n");
            exchange.close();
        })) {
            assertThrows(IOException.class, () -> client(malformed,
                    Duration.ofSeconds(1), Duration.ofSeconds(3)).complete(
                    json.createArrayNode(), json.createArrayNode(), "system", ignored -> { }));
            assertEquals(1, malformed.requests());
        }

        try (StreamServer incomplete = new StreamServer(exchange -> {
            beginChunked(exchange);
            write(exchange, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n");
            exchange.close();
        })) {
            List<String> deltas = new ArrayList<>();
            IOException failure = assertThrows(IOException.class, () -> client(incomplete,
                    Duration.ofSeconds(1), Duration.ofSeconds(3)).complete(
                    json.createArrayNode(), json.createArrayNode(), "system", deltas::add));
            assertTrue(failure.getMessage().contains("without response.completed"), failure.toString());
            assertEquals(List.of("partial"), deltas);
            assertEquals(1, incomplete.requests());
        }

        try (StreamServer oversized = new StreamServer(exchange -> {
            beginChunked(exchange);
            String piece = "data: " + repeat('x', 220_000) + "\n";
            try {
                for (int index = 0; index < 6; index++) write(exchange, piece);
                write(exchange, "\n");
            } catch (IOException clientClosedAfterLimit) {
                // The client should cancel as soon as the event cap is crossed.
            } finally {
                exchange.close();
            }
        })) {
            IOException failure = assertThrows(IOException.class, () -> client(oversized,
                    Duration.ofSeconds(2), Duration.ofSeconds(5)).complete(
                    json.createArrayNode(), json.createArrayNode(), "system", ignored -> { }));
            assertTrue(failure.getMessage().contains("event exceeded the size limit"), failure.toString());
            assertEquals(1, oversized.requests());
        }
    }

    @Test
    void overallDeadlineAppliesEvenWhenCommentsKeepTheConnectionActive() throws Exception {
        CountDownLatch firstComment = new CountDownLatch(1);
        try (StreamServer server = new StreamServer(exchange -> {
            beginChunked(exchange);
            try {
                for (int index = 0; index < 20; index++) {
                    write(exchange, ": keep-alive\n\n");
                    firstComment.countDown();
                    Thread.sleep(40);
                }
            } catch (IOException clientClosedAtDeadline) {
                // Expected after the client enforces its overall deadline.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        })) {
            IOException failure = assertThrows(IOException.class, () -> client(server,
                    Duration.ofMillis(500), Duration.ofMillis(250)).complete(
                    json.createArrayNode(), json.createArrayNode(), "system", ignored -> { }));
            assertTrue(firstComment.await(1, TimeUnit.SECONDS));
            assertTrue(failure.getMessage().contains("overall timeout"), failure.toString());
            assertEquals(1, server.requests());
        }
    }

    private OpenAiResponsesClient client(StreamServer server, Duration idle, Duration overall) {
        AgentConfig config = new AgentConfig("test-key", server.baseUrl(), "model", workspace, 2,
                PermissionMode.ASK);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new OpenAiResponsesClient(json, http, config, ignored -> { }, idle, overall);
    }

    private static void beginChunked(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void write(HttpExchange exchange, String value) throws IOException {
        exchange.getResponseBody().write(value.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class StreamServer implements AutoCloseable {
        private final HttpServer server;
        private final ExchangeHandler handler;
        private final AtomicInteger requests = new AtomicInteger();

        StreamServer(ExchangeHandler handler) throws IOException {
            this.handler = handler;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v1/responses", exchange -> {
                requests.incrementAndGet();
                try {
                    this.handler.handle(exchange);
                } catch (IOException failure) {
                    exchange.close();
                }
            });
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int requests() { return requests.get(); }

        @Override
        public void close() { server.stop(0); }
    }
}
