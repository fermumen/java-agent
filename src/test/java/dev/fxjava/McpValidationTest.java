package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpValidationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void schemasRetainLocalReferencesAndRejectExternalReferences() throws Exception {
        assertDoesNotThrow(() -> McpValidation.schema(json.readTree(
                "{\"type\":\"object\",\"$defs\":{\"x\":{\"type\":\"string\"}},"
                        + "\"properties\":{\"x\":{\"$ref\":\"#/$defs/x\"}}}")));
        assertThrows(IOException.class, () -> McpValidation.schema(json.readTree(
                "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":"
                        + "\"https://example.test/schema\"}}}")));
    }

    @Test
    void toolResultsPreserveSupportedContentAndRejectMalformedPayloads() throws Exception {
        assertDoesNotThrow(() -> McpValidation.toolResult(json.readTree(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"},"
                        + "{\"type\":\"image\",\"data\":\"AA==\",\"mimeType\":\"image/png\"},"
                        + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///a\","
                        + "\"text\":\"body\"}}],\"structuredContent\":{\"ok\":true},\"isError\":false}")));
        assertThrows(IOException.class, () -> McpValidation.toolResult(json.readTree(
                "{\"content\":[{\"type\":\"image\",\"data\":\"not base64\","
                        + "\"mimeType\":\"image/png\"}]}")));
        assertThrows(IOException.class, () -> McpValidation.toolResult(json.readTree(
                "{\"content\":[{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///a\","
                        + "\"text\":\"x\",\"blob\":\"AA==\"}}]}")));
    }
}
