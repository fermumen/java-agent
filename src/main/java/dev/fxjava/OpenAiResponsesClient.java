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
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("instructions", instructions);
        body.set("input", input);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        body.put("store", false);
        body.putArray("include").add("reasoning.encrypted_content");

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenAI returned HTTP " + response.statusCode() + ": "
                    + abbreviate(response.body(), 4_000));
        }

        JsonNode parsed = json.readTree(response.body());
        if (!parsed.isObject()) throw new IOException("OpenAI returned a non-object response");
        ObjectNode result = (ObjectNode) parsed;
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
