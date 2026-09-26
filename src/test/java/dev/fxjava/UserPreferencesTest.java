package dev.fxjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPreferencesTest {
    @TempDir
    Path temporary;

    @Test
    void keyPromptIsSessionOnlyUnlessUserExplicitlyOptsIn() throws Exception {
        Path root = temporary.resolve("session-only");
        char[] secret = "test-secret-key".toCharArray();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        String key = ApiKeyOnboarding.request(prompt(secret, "n"), UserPreferences.empty(root),
                new PrintStream(messages, true, StandardCharsets.UTF_8));

        assertEquals("test-secret-key", key);
        assertArrayEquals(new char[secret.length], secret, "the prompt buffer is cleared");
        assertFalse(Files.exists(root.resolve("user-settings.json")));
        assertFalse(messages.toString(StandardCharsets.UTF_8).contains(key));
    }

    @Test
    void optedInKeyAndPreferencesRoundTripWithPrivateFilePermissions() throws Exception {
        Path root = temporary.resolve("saved");
        char[] secret = "test-secret-key".toCharArray();
        ByteArrayOutputStream messages = new ByteArrayOutputStream();
        String key = ApiKeyOnboarding.request(prompt(secret, "yes"), UserPreferences.empty(root),
                new PrintStream(messages, true, StandardCharsets.UTF_8));

        UserPreferences saved = UserPreferences.load(root);
        assertEquals(key, saved.apiKey());
        saved = saved.withModel("custom-model").withReasoningEffort("high");
        saved.save();
        UserPreferences reloaded = UserPreferences.load(root);
        assertEquals(key, reloaded.apiKey());
        assertEquals("custom-model", reloaded.model());
        assertEquals("high", reloaded.reasoningEffort());
        assertFalse(messages.toString(StandardCharsets.UTF_8).contains(key));

        try {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(reloaded.file()));
        } catch (UnsupportedOperationException noPosixPermissions) {
            // ACL-capable filesystems use an owner-only ACL instead.
        }
    }

    private static ApiKeyOnboarding.Prompt prompt(char[] secret, String consent) {
        return new ApiKeyOnboarding.Prompt() {
            @Override public char[] readPassword(String prompt) { return secret; }
            @Override public String readLine(String prompt) { return consent; }
        };
    }
}
