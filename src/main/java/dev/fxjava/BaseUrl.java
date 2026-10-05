package dev.fxjava;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates the Responses API base URL before it reaches the HTTP client. */
final class BaseUrl {
    static final String DEFAULT = "https://api.openai.com/v1";

    private BaseUrl() { }

    /** Returns the URL without trailing slashes, or throws with a fix-it message. */
    static String validate(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("base URL must not be blank");
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("base URL is not a valid URL: " + trimmed);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("base URL must be an absolute http(s) URL, for example " + DEFAULT);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("base URL must not contain a query or fragment; for Azure OpenAI use "
                    + "the v1 endpoint https://<resource>.openai.azure.com/openai/v1 without ?api-version");
        }
        return trimmed.replaceAll("/+$", "");
    }
}
