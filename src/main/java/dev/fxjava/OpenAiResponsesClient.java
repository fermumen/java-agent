package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Dependency-light OpenAI Responses API client backed by the JDK HTTP client. */
public final class OpenAiResponsesClient implements ResponsesClient {
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiResponsesClient(ObjectMapper json, AgentConfig config) {
        this(json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), config);
    }

    OpenAiResponsesClient(ObjectMapper json, HttpClient http, AgentConfig config) {
        this.json = json;
        this.http = http;
        this.endpoint = responsesEndpoint(config.baseUrl());
        this.apiKey = config.apiKey();
        this.model = config.model();
    }

    @Override
    public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
            throws IOException, InterruptedException {
        ObjectNode body = requestBody(input, tools, instructions, false);
        HttpResponse<String> response = http.send(request(body, "application/json"),
                HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), response.body());
        JsonNode parsed = json.readTree(response.body());
        if (!parsed.isObject()) throw new IOException("OpenAI returned a non-object response");
        return validate((ObjectNode) parsed);
    }

    @Override
    public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions,
                               Consumer<String> textDelta)
            throws IOException, InterruptedException {
        ObjectNode body = requestBody(input, tools, instructions, true);
        HttpResponse<Stream<String>> response = http.send(request(body, "text/event-stream"),
                HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = lines.limit(128).reduce("", (left, right) -> left + right + "\n");
                requireSuccess(response.statusCode(), message);
            }
            Iterable<String> iterable = () -> lines.iterator();
            return validate(parseEventStream(json, iterable, textDelta));
        }
    }

    static ObjectNode parseEventStream(ObjectMapper json, Iterable<String> lines,
                                       Consumer<String> textDelta) throws IOException {
        StringBuilder data = new StringBuilder();
        ObjectNode completed = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                ObjectNode terminal = processEvent(json, data, textDelta);
                if (terminal != null) completed = terminal;
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        ObjectNode terminal = processEvent(json, data, textDelta);
        if (terminal != null) completed = terminal;
        if (completed == null) throw new IOException("OpenAI stream ended without response.completed");
        return completed;
    }

    private static ObjectNode processEvent(ObjectMapper json, StringBuilder data,
                                           Consumer<String> textDelta) throws IOException {
        if (data.isEmpty() || data.toString().equals("[DONE]")) return null;
        JsonNode event = json.readTree(data.toString());
        String type = event.path("type").asText();
        if (type.equals("response.output_text.delta")) {
            String delta = event.path("delta").asText();
            if (!delta.isEmpty()) textDelta.accept(delta);
            return null;
        }
        if (type.equals("response.completed")) {
            JsonNode response = event.path("response");
            if (!response.isObject()) throw new IOException("response.completed omitted its response object");
            return (ObjectNode) response;
        }
        if (type.equals("error") || type.equals("response.failed") || type.equals("response.incomplete")) {
            JsonNode response = event.path("response");
            String message = firstNonBlank(event.path("message").asText(),
                    event.path("error").path("message").asText(),
                    response.path("error").path("message").asText(),
                    response.path("incomplete_details").path("reason").asText());
            throw new IOException("OpenAI stream " + type + (message == null ? "" : ": " + message));
        }
        return null;
    }

    private ObjectNode requestBody(ArrayNode input, ArrayNode tools, String instructions, boolean stream) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("instructions", instructions);
        body.set("input", input);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        body.put("store", false);
        body.put("stream", stream);
        body.putArray("include").add("reasoning.encrypted_content");
        return body;
    }

    private HttpRequest request(ObjectNode body, String accept) throws IOException {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }

    private static ObjectNode validate(ObjectNode result) throws IOException {
        String status = result.path("status").asText("completed");
        if (!status.equals("completed")) {
            String detail = firstNonBlank(result.path("error").path("message").asText(),
                    result.path("incomplete_details").path("reason").asText());
            throw new IOException("OpenAI response status was " + status
                    + (detail == null ? "" : ": " + detail));
        }
        if (!result.path("output").isArray()) {
            throw new IOException("OpenAI response did not contain an output array");
        }
        return result;
    }

    private static void requireSuccess(int statusCode, String body) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("OpenAI returned HTTP " + statusCode + ": " + abbreviate(body, 4_000));
        }
    }

    static URI responsesEndpoint(String value) {
        String base = value.replaceAll("/+$", "");
        return URI.create(base.endsWith("/responses") ? base : base + "/responses");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
