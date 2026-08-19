package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiResponsesClientTest {
    @Test
    void appendsResponsesToABaseUrl() {
        assertEquals("https://api.openai.test/v1/responses",
                OpenAiResponsesClient.responsesEndpoint("https://api.openai.test/v1/").toString());
    }

    @Test
    void acceptsACompleteEndpoint() {
        assertEquals("https://api.openai.test/v1/responses",
                OpenAiResponsesClient.responsesEndpoint(
                        "https://api.openai.test/v1/responses").toString());
    }
}
