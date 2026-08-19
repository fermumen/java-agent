package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded direct public-HTTP fetcher; it never uses Gateway. */
final class WebFetchTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RESULT_CHARS = 200_000;
    private static final int MAX_REDIRECTS = 5;
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 128;
    private final HttpClient http;
    private final boolean allowPrivateForTests;
    private final ObjectNode parameters;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    WebFetchTool() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), false);
    }

    WebFetchTool(HttpClient http, boolean allowPrivateForTests) {
        this.http = http;
        this.allowPrivateForTests = allowPrivateForTests;
        parameters = JSON.createObjectNode().put("type", "object");
        parameters.putObject("properties").putObject("url").put("type", "string")
                .put("description", "Known public HTTP(S) URL to fetch.");
        parameters.putArray("required").add("url");
        parameters.put("additionalProperties", false);
    }

    @Override public String name() { return "web_fetch"; }
    @Override public String description() {
        return "Fetch a known public HTTP(S) URL directly with bounded redirects and content.";
    }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String preview(JsonNode arguments) { return "fetch " + display(arguments.path("url").asText()); }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (!arguments.isObject() || arguments.size() != 1 || !arguments.path("url").isTextual()) {
            throw new IllegalArgumentException("web_fetch accepts only the required string field url");
        }
        URI submitted = validate(arguments.path("url").asText());
        Cached cached;
        synchronized (cache) {
            cached = cache.get(submitted.toString());
            if (cached != null && cached.expiresAtMs() <= System.currentTimeMillis()) {
                cache.remove(submitted.toString());
                cached = null;
            }
        }
        if (cached != null) return render(cached.result(), true);

        URI current = submitted;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(30))
                    .header("Accept", "text/html, text/plain, application/json, application/xml;q=0.9, */*;q=0.1")
                    .header("User-Agent", "java-agent/0.2 web_fetch").GET().build();
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            int status = response.statusCode();
            if (status >= 300 && status <= 399) {
                try (InputStream ignored = response.body()) { }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("web_fetch redirect omitted Location"));
                if (redirects == MAX_REDIRECTS) throw new IOException("web_fetch exceeded 5 redirects");
                current = validate(current.resolve(location).toString());
                continue;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > MAX_BODY_BYTES) {
                response.body().close();
                throw new IOException("web_fetch response exceeds 10 MiB");
            }
            byte[] body;
            try (InputStream input = response.body()) {
                body = readBounded(input);
            }
            String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!textual(mime)) {
                throw new IOException("web_fetch does not inline binary content (" + mime + ", " + body.length + " bytes)");
            }
            String content = decode(body, contentType);
            if (mime.equals("text/html") || mime.equals("application/xhtml+xml")) content = htmlToText(content);
            boolean truncated = content.length() > MAX_RESULT_CHARS;
            if (truncated) content = content.substring(0, MAX_RESULT_CHARS);
            Result result = new Result(submitted.toString(), current.toString(), status, mime, content, truncated);
            synchronized (cache) {
                cache.put(submitted.toString(), new Cached(result, System.currentTimeMillis() + CACHE_TTL_MS));
            }
            return render(result, false);
        }
        throw new IOException("web_fetch redirect loop");
    }

    private URI validate(String raw) throws IOException {
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("web_fetch URL is invalid", invalid);
        }
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            if (uri.getUserInfo() != null) throw new IOException("web_fetch rejects credential-bearing URLs");
            throw new IOException("web_fetch requires an HTTP(S) URL without a fragment");
        }
        if (allowPrivateForTests) return uri.normalize();
        String literalHost = uri.getHost();
        String host = (literalHost.contains(":") ? literalHost : IDN.toASCII(literalHost)).toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw new IOException("web_fetch requires a public host");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            if (uniqueLocalV6 || address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IOException("web_fetch requires a public host");
            }
        }
        return uri.normalize();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int count; (count = input.read(buffer)) >= 0;) {
            total += count;
            if (total > MAX_BODY_BYTES) throw new IOException("web_fetch response exceeds 10 MiB");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean textual(String mime) {
        return mime.startsWith("text/") || mime.equals("application/json") || mime.endsWith("+json")
                || mime.equals("application/xml") || mime.endsWith("+xml") || mime.equals("application/javascript");
    }

    private static String decode(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        for (String part : contentType.split(";")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try { charset = Charset.forName(value.substring(8).replace("\"", "")); }
                catch (Exception ignored) { }
            }
        }
        return new String(body, charset);
    }

    private static String htmlToText(String html) {
        return html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String render(Result result, boolean cacheHit) throws IOException {
        ObjectNode value = JSON.createObjectNode().put("submitted_url", result.submittedUrl())
                .put("final_url", result.finalUrl()).put("status", result.status())
                .put("mime_type", result.mime()).put("content", result.content())
                .put("cache_hit", cacheHit).put("truncated", result.truncated())
                .put("trust", "untrusted_external");
        return JSON.writeValueAsString(value);
    }

    private static String display(String raw) {
        try {
            URI uri = URI.create(raw);
            if (uri.getUserInfo() == null) return raw;
            return uri.getScheme() + "://[redacted]@" + uri.getHost() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (Exception invalid) {
            return "URL";
        }
    }

    private record Result(String submittedUrl, String finalUrl, int status, String mime,
                          String content, boolean truncated) { }
    private record Cached(Result result, long expiresAtMs) { }
}
