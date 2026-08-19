package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void followsRevalidatedRedirectConvertsHtmlAndCachesBySubmittedUrl() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture fixture = new Fixture(requests)) {
            WebFetchTool tool = new WebFetchTool(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(), true);
            JsonNode first = json.readTree(tool.execute(json.createObjectNode().put("url", fixture.url("/redirect"))));
            JsonNode second = json.readTree(tool.execute(json.createObjectNode().put("url", fixture.url("/redirect"))));

            assertEquals(200, first.path("status").asInt());
            assertEquals("text/html", first.path("mime_type").asText());
            assertTrue(first.path("content").asText().contains("Title"));
            assertTrue(first.path("content").asText().contains("Hello & goodbye"));
            assertEquals("untrusted_external", first.path("trust").asText());
            assertTrue(!first.path("cache_hit").asBoolean() && second.path("cache_hit").asBoolean());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void rejectsCredentialsPrivateTargetsAndUnknownFieldsBeforeTransport() {
        WebFetchTool publicOnly = new WebFetchTool();
        assertTrue(assertThrows(IOException.class, () -> publicOnly.execute(
                json.createObjectNode().put("url", "http://127.0.0.1/private"))).getMessage().contains("public"));
        assertTrue(assertThrows(IOException.class, () -> publicOnly.execute(
                json.createObjectNode().put("url", "https://user:pass@example.com/docs")))
                .getMessage().contains("credential-bearing"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> publicOnly.execute(
                json.createObjectNode().put("url", "https://example.com").put("prompt", "legacy")))
                .getMessage().contains("only"));
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;

        Fixture(AtomicInteger requests) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/redirect", exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().add("Location", "/page");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/page", exchange -> {
                requests.incrementAndGet();
                send(exchange, "<html><style>x{}</style><h1>Title</h1><p>Hello &amp; goodbye</p></html>");
            });
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        private static void send(HttpExchange exchange, String value) throws IOException {
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override public void close() { server.stop(0); }
    }
}
