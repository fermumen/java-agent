package dev.fxjava;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

/** Hidden first-run key entry; persistence happens only after an explicit yes. */
final class ApiKeyOnboarding {
    private static final int BASE_URL_ATTEMPTS = 3;

    interface Prompt {
        char[] readPassword(String prompt) throws IOException;
        String readLine(String prompt) throws IOException;
    }

    /** Entered credentials; {@code baseUrl} is null when the user kept the default. */
    static final class Credentials {
        final String apiKey;
        final String baseUrl;

        Credentials(String apiKey, String baseUrl) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }
    }

    static String request(Prompt prompt, UserPreferences preferences, PrintStream error) throws IOException {
        Credentials credentials = request(prompt, preferences, error, false);
        return credentials == null ? null : credentials.apiKey;
    }

    /**
     * Asks for the API key and, when {@code askBaseUrl} is set, the endpoint first. A
     * yes at the save prompt stores both.
     */
    static Credentials request(Prompt prompt, UserPreferences preferences, PrintStream error, boolean askBaseUrl)
            throws IOException {
        error.println("No API key is configured. Enter it at the hidden prompt, or leave blank to cancel.");
        String baseUrl = null;
        if (askBaseUrl) {
            for (int attempt = 1; attempt <= BASE_URL_ATTEMPTS; attempt++) {
                String entered = prompt.readLine("API base URL (Enter for " + BaseUrl.DEFAULT
                        + "; Azure: https://<resource>.openai.azure.com/openai/v1): ");
                if (entered == null || entered.isBlank()) break;
                try {
                    baseUrl = BaseUrl.validate(entered);
                    break;
                } catch (IllegalArgumentException invalid) {
                    error.println("java-agent: " + invalid.getMessage());
                    if (attempt == BASE_URL_ATTEMPTS) return null;
                }
            }
        }
        char[] entered = prompt.readPassword("API key: ");
        if (entered == null) return null;
        String apiKey;
        try {
            apiKey = new String(entered).strip();
        } finally {
            Arrays.fill(entered, '\0');
        }
        if (apiKey.isBlank()) return null;

        String saved = baseUrl == null ? "this key" : "this key and base URL";
        String consent = prompt.readLine("Save " + saved + " as unencrypted text with private permissions for future runs in "
                + preferences.file() + "? [y/N] ");
        if (consent != null && (consent.equalsIgnoreCase("y") || consent.equalsIgnoreCase("yes"))) {
            try {
                UserPreferences updated = preferences.withApiKey(apiKey);
                if (baseUrl != null) updated = updated.withBaseUrl(baseUrl);
                updated.save();
                error.println((baseUrl == null ? "API key" : "API key and base URL")
                        + " saved as unencrypted text with private file permissions.");
            } catch (IOException unableToSave) {
                error.println("java-agent: could not securely save the API key; it will be used for this run only.");
            }
        }
        return new Credentials(apiKey, baseUrl);
    }

    private ApiKeyOnboarding() { }
}
