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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseUrlConfigTest {
    private static final String AZURE = "https://example-resource.openai.azure.com/openai/v1";
    private final ObjectMapper json = new ObjectMapper();

    @TempDir Path temporary;

    @Test
    void validatesAndNormalizesBaseUrls() {
        assertEquals(AZURE, BaseUrl.validate(" " + AZURE + "/ "));
        assertEquals("http://127.0.0.1:8080/v1/responses", BaseUrl.validate("http://127.0.0.1:8080/v1/responses"));
        IllegalArgumentException query = assertThrows(IllegalArgumentException.class,
                () -> BaseUrl.validate(AZURE + "?api-version=2025-04-01-preview"));
        assertTrue(query.getMessage().contains("without ?api-version"), query.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BaseUrl.validate("ftp://example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrl.validate("example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrl.validate("  "));
    }

    @Test
    void savedBaseUrlRoundTripsWithoutDisturbingOtherSettings() throws Exception {
        Path home = temporary.resolve("home");
        UserPreferences.empty(home).withApiKey("private-test-key").withModel("m").save();
        UserPreferences.load(home).withBaseUrl(AZURE).save();

        UserPreferences reloaded = UserPreferences.load(home);
        assertEquals(AZURE, reloaded.baseUrl());
        assertEquals("private-test-key", reloaded.apiKey());
        assertEquals("m", reloaded.model());
        assertNull(reloaded.withBaseUrl(null).baseUrl());
    }

    @Test
    void configCommandSetsShowsAndUnsetsTheSavedBaseUrlWithoutPrintingTheKey() throws Exception {
        Path home = temporary.resolve("home");
        UserPreferences.empty(home).withApiKey("private-test-key").save();
        Map<String, String> environment = Map.of("JAVA_AGENT_HOME", home.toString());

        JsonNode set = runJson(environment, "config", "set", "base-url", AZURE + "/", "--json");
        assertEquals("set", set.path("action").asText());
        assertEquals(AZURE, set.path("base_url").asText());
        assertEquals(AZURE, UserPreferences.load(home).baseUrl());
        assertEquals("private-test-key", UserPreferences.load(home).apiKey());

        JsonNode shown = runJson(environment, "config", "--json");
        assertEquals("saved", shown.path("api_key").asText());
        assertEquals(AZURE, shown.path("base_url").asText());
        assertEquals("saved", shown.path("base_url_source").asText());
        assertFalse(shown.toString().contains("private-test-key"));

        JsonNode status = runJson(environment, "status", "--json");
        assertEquals(AZURE, status.path("base_url").asText());

        JsonNode overridden = runJson(Map.of("JAVA_AGENT_HOME", home.toString(),
                "OPENAI_BASE_URL", "https://other.example/v1"), "config", "show", "--json");
        assertEquals("https://other.example/v1", overridden.path("base_url").asText());
        assertEquals("env:OPENAI_BASE_URL", overridden.path("base_url_source").asText());

        runJson(environment, "config", "unset", "base-url", "--json");
        assertNull(UserPreferences.load(home).baseUrl());
        assertEquals("private-test-key", UserPreferences.load(home).apiKey());
        assertEquals(BaseUrl.DEFAULT, runJson(environment, "status", "--json").path("base_url").asText());
    }

    @Test
    void configRejectsInvalidUrlsAndUnknownActionsWithoutWriting() throws Exception {
        Path home = temporary.resolve("home");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = runCode(Map.of("JAVA_AGENT_HOME", home.toString()), errors,
                "config", "set", "base-url", AZURE + "?api-version=1");
        assertTrue(code != 0);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("?api-version"));
        assertFalse(Files.exists(home.resolve("user-settings.json")));

        int unknown = runCode(Map.of("JAVA_AGENT_HOME", home.toString()), new ByteArrayOutputStream(),
                "config", "set", "model", "x");
        assertTrue(unknown != 0);
    }

    @Test
    void savedBaseUrlRoutesRequestsAndEnvironmentStillWins() throws Exception {
        Path home = temporary.resolve("home");
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        try (FakeResponsesServer api = new FakeResponsesServer(0)) {
            UserPreferences.empty(home).withBaseUrl(api.baseUrl()).save();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int code = Main.run(new String[]{"--workspace", workspace.toString(), "--yolo", "--json",
                            "Create the smoke marker"},
                    Map.of("OPENAI_API_KEY", "test-key", "JAVA_AGENT_HOME", home.toString()),
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            assertEquals(0, code, output.toString(StandardCharsets.UTF_8));
            assertTrue(api.requestCount() > 0, "the saved base URL received the request");

            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int invalid = Main.run(new String[]{"--workspace", workspace.toString(), "--json", "hi"},
                    Map.of("OPENAI_API_KEY", "test-key", "JAVA_AGENT_HOME", home.toString(),
                            "OPENAI_BASE_URL", AZURE + "?api-version=1"),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(errors, true, StandardCharsets.UTF_8));
            assertEquals(2, invalid);
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("must not contain a query"));
        }
    }

    @Test
    void onboardingAsksForTheBaseUrlAndSavesItWithTheKey() throws Exception {
        Path home = temporary.resolve("home");
        Deque<String> lines = new ArrayDeque<>(List.of("example.com/v1", AZURE + "/", "yes"));
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        ApiKeyOnboarding.Credentials credentials = ApiKeyOnboarding.request(new ApiKeyOnboarding.Prompt() {
            @Override public char[] readPassword(String prompt) { return "azure-key".toCharArray(); }
            @Override public String readLine(String prompt) { return lines.removeFirst(); }
        }, UserPreferences.empty(home), new PrintStream(messages, true, StandardCharsets.UTF_8), true);

        assertEquals("azure-key", credentials.apiKey);
        assertEquals(AZURE, credentials.baseUrl);
        assertTrue(messages.toString(StandardCharsets.UTF_8).contains("must be an absolute http(s) URL"));
        UserPreferences saved = UserPreferences.load(home);
        assertEquals("azure-key", saved.apiKey());
        assertEquals(AZURE, saved.baseUrl());

        Deque<String> defaults = new ArrayDeque<>(List.of("", "n"));
        ApiKeyOnboarding.Credentials keptDefault = ApiKeyOnboarding.request(new ApiKeyOnboarding.Prompt() {
            @Override public char[] readPassword(String prompt) { return "k".toCharArray(); }
            @Override public String readLine(String prompt) { return defaults.removeFirst(); }
        }, UserPreferences.empty(temporary.resolve("other")), new PrintStream(new ByteArrayOutputStream()), true);
        assertNull(keptDefault.baseUrl);
    }

    private JsonNode runJson(Map<String, String> environment, String... args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, Main.run(args, environment, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8)), errors.toString(StandardCharsets.UTF_8));
        return json.readTree(output.toString(StandardCharsets.UTF_8));
    }

    private int runCode(Map<String, String> environment, ByteArrayOutputStream errors, String... args) {
        try {
            return Main.run(args, environment, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(errors, true, StandardCharsets.UTF_8));
        } catch (Exception thrown) {
            errors.writeBytes(String.valueOf(thrown.getMessage()).getBytes(StandardCharsets.UTF_8));
            return 1;
        }
    }
}
