package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Relevant cases ported from fx/core/shared/text_utils.zig. */
class SecretRedactorTest {
    @Test
    void masksEnvironmentAndQuotedAssignments() {
        assertEquals("AI_GATEWAY_API_KEY=[redacted] end",
                SecretRedactor.mask("AI_GATEWAY_API_KEY=abcdefghijklmnop end"));
        assertEquals("API_KEY=\"[redacted]\"\nPASSWORD='[redacted]'\nACCESS_TOKEN=\"[redacted]\"",
                SecretRedactor.mask("API_KEY=\"double-secret\"\nPASSWORD='single-secret'\n"
                        + "ACCESS_TOKEN=\"access-secret\""));
    }

    @Test
    void preservesBenignAssignmentsAndInlineKeySubstrings() {
        String benign = "PROJECT_NAME=\"secret-service\"\nGREETING='hello world'\n"
                + "printf output > ask-turn-default-auto.txt";
        assertEquals(benign, SecretRedactor.mask(benign));
    }

    @Test
    void masksInlineProviderTokens() {
        assertEquals("token [redacted] now", SecretRedactor.mask("token sk-abcdefghijklmnop now"));
        assertEquals("github=[redacted]",
                SecretRedactor.mask("github=ghs_abcdefghijklmnopqrstuvwxyz0123456789AB"));
        assertEquals("bearer [redacted]",
                SecretRedactor.mask("bearer Bearer abcdefghijklmnop"));
    }

    @Test
    void masksAwsBasicAuthAndGenericSensitiveAssignments() {
        String raw = "aws=AKIA0123456789ABCDEF\n"
                + "url=https://user:token@example.com/path\n"
                + "password=hunter2\nCUSTOM_API_KEY=abc123";
        String masked = SecretRedactor.mask(raw);
        assertEquals("aws=[redacted]\nurl=https://[redacted]@example.com/path\n"
                + "password=[redacted]\nCUSTOM_API_KEY=[redacted]", masked);
        assertFalse(masked.contains("AKIA0123456789ABCDEF"));
    }

    @Test
    void redactsCredentialedUrlsAndSensitiveQueryKeys() {
        assertEquals("https://[redacted]@example.com/docs?safe=ok&token=[redacted]"
                        + "&X-Amz-%43redential=[redacted]&X-Amz-Signature=[redacted]",
                SecretRedactor.url("https://user:pass@example.com/docs?safe=ok&token=abc123"
                        + "&X-Amz-%43redential=credential-value&X-Amz-Signature=signature-value"));
        assertEquals("https://example.com/docs?design=blue&sig=[redacted]",
                SecretRedactor.url("https://example.com/docs?design=blue&sig=secret"));
    }

    @Test
    void structuredArgumentsStayValidAndPreserveBenignFields() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String redacted = SecretRedactor.arguments(json, "run_command",
                "{\"command\":\"echo ok\",\"api_key\":\"secret-value\","
                        + "\"nested\":{\"password\":\"hidden\"}}");
        JsonNode parsed = json.readTree(redacted);
        assertEquals("echo ok", parsed.path("command").asText());
        assertEquals("[redacted]", parsed.path("api_key").asText());
        assertEquals("[redacted]", parsed.path("nested").path("password").asText());
        assertFalse(redacted.contains("secret-value"));
        assertFalse(redacted.contains("hidden"));
    }
}
