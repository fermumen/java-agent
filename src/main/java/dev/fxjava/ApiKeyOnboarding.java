package dev.fxjava;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

/** Hidden first-run key entry; persistence happens only after an explicit yes. */
final class ApiKeyOnboarding {
    interface Prompt {
        char[] readPassword(String prompt) throws IOException;
        String readLine(String prompt) throws IOException;
    }

    static String request(Prompt prompt, UserPreferences preferences, PrintStream error) throws IOException {
        error.println("No API key is configured. Enter it at the hidden prompt, or leave blank to cancel.");
        char[] entered = prompt.readPassword("OpenAI API key: ");
        if (entered == null) return null;
        String apiKey;
        try {
            apiKey = new String(entered).strip();
        } finally {
            Arrays.fill(entered, '\0');
        }
        if (apiKey.isBlank()) return null;

        String consent = prompt.readLine("Save this key as unencrypted text with private permissions for future runs in "
                + preferences.file() + "? [y/N] ");
        if (consent != null && (consent.equalsIgnoreCase("y") || consent.equalsIgnoreCase("yes"))) {
            try {
                preferences.withApiKey(apiKey).save();
                error.println("API key saved as unencrypted text with private file permissions.");
            } catch (IOException unableToSave) {
                error.println("java-agent: could not securely save the API key; it will be used for this run only.");
            }
        }
        return apiKey;
    }

    private ApiKeyOnboarding() { }
}
