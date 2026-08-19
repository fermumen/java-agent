package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponsesStreamingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emitsTextDeltasAndReturnsCompletedResponse() throws Exception {
        List<String> deltas = new ArrayList<>();
        ObjectNode response = OpenAiResponsesClient.parseEventStream(json, List.of(
                "event: response.created",
                "data: {\"type\":\"response.created\"}",
                "",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hel\"}",
                "",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}",
                "",
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",",
                "data: \"output\":[{\"type\":\"message\",\"content\":[]}]}}",
                "",
                "data: [DONE]"), deltas::add);

        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals("completed", response.path("status").asText());
        assertEquals("message", response.path("output").path(0).path("type").asText());
    }

    @Test
    void surfacesFailedStreamDetail() {
        IOException error = assertThrows(IOException.class, () ->
                OpenAiResponsesClient.parseEventStream(json, List.of(
                        "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"bad request\"}}}",
                        ""), ignored -> { }));
        assertEquals("OpenAI stream response.failed: bad request", error.getMessage());
    }

    @Test
    void rejectsTruncatedStreamWithoutCompletedEvent() {
        IOException error = assertThrows(IOException.class, () ->
                OpenAiResponsesClient.parseEventStream(json, List.of(
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}"),
                        ignored -> { }));
        assertEquals("OpenAI stream ended without response.completed", error.getMessage());
    }
}
