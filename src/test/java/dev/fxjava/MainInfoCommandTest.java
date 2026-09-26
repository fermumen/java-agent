package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainInfoCommandTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void statusAndPermissionsNeedNoApiKeyAndReportNoGateway() throws Exception {
        JsonNode status = runJson(new String[]{"status", "--json", "--workspace", temporary.toString()}, Map.of());
        assertEquals("status", status.path("kind").asText());
        assertEquals("responses", status.path("transport").asText());
        assertFalse(status.path("gateway").asBoolean());
        assertEquals("ask", status.path("permission_mode").asText());

        JsonNode permissions = runJson(new String[]{"permissions", "--json"},
                Map.of("JAVA_AGENT_PERMISSION_MODE", "auto"));
        assertEquals("auto", permissions.path("mode").asText());
        assertEquals(0, permissions.path("grant_count").asInt());
        assertTrue(permissions.path("rules").isArray());
        assertEquals("none", permissions.path("rules_scope").asText());
    }

    @Test
    void doctorIsReadOnlyAndReportsMissingAuthentication() throws Exception {
        Path home = temporary.resolve("unused-state");
        JsonNode doctor = runJson(new String[]{"doctor", "--json", "--workspace", temporary.toString()},
                Map.of("JAVA_AGENT_HOME", home.toString()));
        assertEquals("doctor", doctor.path("kind").asText());
        assertEquals(1, doctor.path("fail_count").asInt());
        assertTrue(doctor.path("checks").toString().contains("OpenAI API key is not configured"));
        assertFalse(Files.exists(home));
    }

    @Test
    void doctorAndStatusReflectSavedSettingsWithoutPrintingTheApiKey() throws Exception {
        Path home = temporary.resolve("saved-config");
        UserPreferences.empty(home).withApiKey("private-test-key")
                .withModel("saved-model").withReasoningEffort("high").save();

        JsonNode doctor = runJson(new String[]{"doctor", "--json", "--workspace", temporary.toString()},
                Map.of("JAVA_AGENT_HOME", home.toString()));
        assertEquals(0, doctor.path("fail_count").asInt());
        assertTrue(doctor.toString().contains("OpenAI API key available"));
        assertFalse(doctor.toString().contains("private-test-key"));

        JsonNode savedStatus = runJson(new String[]{"status", "--json"},
                Map.of("JAVA_AGENT_HOME", home.toString()));
        assertEquals("saved-model", savedStatus.path("model").asText());
        assertEquals("high", savedStatus.path("reasoning_effort").asText());

        JsonNode envStatus = runJson(new String[]{"status", "--json"},
                Map.of("JAVA_AGENT_HOME", home.toString(), "OPENAI_MODEL", "env-model",
                        "OPENAI_REASONING_EFFORT", "low"));
        assertEquals("env-model", envStatus.path("model").asText());
        assertEquals("low", envStatus.path("reasoning_effort").asText());
        assertFalse(envStatus.toString().contains("private-test-key"));
    }

    @Test
    void cliRejectsUnsupportedEffortWithSupportedChoices() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"--effort", "automatic", "ask", "hello"},
                Map.of("OPENAI_API_KEY", "test-key", "JAVA_AGENT_HOME", temporary.resolve("state").toString()),
                new PrintStream(output), new PrintStream(errors));
        assertEquals(2, code);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("none, minimal, low, medium, high, xhigh, max"));
    }

    @Test
    void sessionsListingIsNoAuthAndDoesNotCreateEmptyState() throws Exception {
        Path state = temporary.resolve("session-state");
        JsonNode empty = runJson(new String[]{"sessions", "--json"}, Map.of("JAVA_AGENT_HOME", state.toString()));
        assertEquals(0, empty.path("count").asInt());
        assertFalse(Files.exists(state));

        SessionStore store = new SessionStore(json, state);
        store.create(temporary, "gpt-test", "instructions");
        store.create(temporary, "gpt-test", "instructions");
        JsonNode first = runJson(new String[]{"sessions", "--json", "--limit", "1"},
                Map.of("JAVA_AGENT_HOME", state.toString()));
        assertEquals(1, first.path("count").asInt());
        assertTrue(first.path("has_more").asBoolean());
        JsonNode second = runJson(new String[]{"sessions", "--json", "--limit", "1",
                        "--cursor", first.path("next_cursor").asText()},
                Map.of("JAVA_AGENT_HOME", state.toString()));
        assertEquals(1, second.path("count").asInt());
        assertEquals("gpt-test", second.path("sessions").path(0).path("model").asText());
    }

    @Test
    void explicitAskKeepsCommandWordsAsPrompts() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"ask", "status"}, Map.of(),
                new PrintStream(output), new PrintStream(errors));
        assertEquals(2, code);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("OPENAI_API_KEY"));
    }

    private JsonNode runJson(String[] args, Map<String, String> environment) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args, environment,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8)));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        return json.readTree(output.toString(StandardCharsets.UTF_8));
    }
}
