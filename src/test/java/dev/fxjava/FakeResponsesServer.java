package dev.fxjava;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeResponsesServer implements AutoCloseable {
    private static final String TOOL_RESPONSE = """
            {"id":"resp_1","object":"response","status":"completed","output":[
              {"id":"rs_1","type":"reasoning","encrypted_content":"encrypted-state","summary":[]},
              {"id":"fc_1","type":"function_call","call_id":"call-smoke","name":"write_file",
               "arguments":"{\\\"path\\\":\\\"smoke.txt\\\",\\\"content\\\":\\\"created by responses smoke test\\\\n\\\"}",
               "status":"completed"}]}
            """;
    private static final String FINAL_RESPONSE = """
            {"id":"resp_2","object":"response","status":"completed","output":[
              {"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[
                {"type":"output_text","text":"Responses smoke test complete","annotations":[]}]}]}
            """;

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    FakeResponsesServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/responses", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int number = requests.incrementAndGet();
        if (!request.contains("\"store\":false")
                || !request.contains("reasoning.encrypted_content")) {
            byte[] error = "missing stateless Responses fields".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
            return;
        }
        if (number == 2 && (!request.contains("function_call_output")
                || !request.contains("encrypted-state"))) {
            byte[] error = "missing replayed response items".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
            return;
        }
        byte[] body = (number == 1 ? TOOL_RESPONSE : FINAL_RESPONSE).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        new FakeResponsesServer(port);
        Thread.currentThread().join();
    }
}
