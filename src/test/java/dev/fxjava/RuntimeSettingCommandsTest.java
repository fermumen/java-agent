package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSettingCommandsTest {
    @TempDir
    Path temporary;

    @Test
    void selectorsChangeTheLiveSessionAndSaveOnlyOnExplicitRequest() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path settingsRoot = temporary.resolve("settings");
        UserPreferences.empty(settingsRoot).withApiKey("saved-key").save();
        ObjectMapper json = new ObjectMapper();
        AgentConfig config = new AgentConfig("in-memory-key", "http://127.0.0.1/v1", "initial-model",
                workspace, 2, PermissionMode.ASK);
        OpenAiResponsesClient client = new OpenAiResponsesClient(json, HttpClient.newHttpClient(), config,
                ignored -> { });
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 2, "instructions");
        SessionRuntime session = SessionRuntime.start(agent, null, workspace, "initial-model", "instructions", null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        PrintStream error = new PrintStream(errors, true, StandardCharsets.UTF_8);

        String source = RuntimeSettingCommands.model(session, "custom-model", settingsRoot,
                "default", out, error);
        assertEquals("session override", source);
        assertEquals("custom-model", session.model());
        assertEquals("saved-key", UserPreferences.load(settingsRoot).apiKey());
        assertNull(UserPreferences.load(settingsRoot).model());

        String effortSource = RuntimeSettingCommands.effort(session, "high", settingsRoot,
                "provider default", out, error);
        assertEquals("session override", effortSource);
        assertEquals("high", session.reasoningEffort());
        RuntimeSettingCommands.model(session, "saved-model --save", settingsRoot,
                source, out, error);
        RuntimeSettingCommands.effort(session, "max --save", settingsRoot,
                effortSource, out, error);
        UserPreferences saved = UserPreferences.load(settingsRoot);
        assertEquals("saved-key", saved.apiKey(), "saving a preference must preserve the opted-in key");
        assertEquals("saved-model", saved.model());
        assertEquals("max", saved.reasoningEffort());
        assertEquals("saved-model", session.model());
        assertEquals("max", session.reasoningEffort());
        assertTrue(errors.toString(StandardCharsets.UTF_8).isEmpty());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("saved-key"));
    }
}
