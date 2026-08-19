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
