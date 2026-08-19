package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

/** Compact port of fx's model-facing secret pattern masker. */
final class SecretRedactor {
    private static final List<String> INLINE_PREFIXES = List.of(
            "sk-", "sk_live_", "pk_live_", "github_pat_", "xoxb-", "xoxp-", "Bearer ");
    private static final List<String> GITHUB_PREFIXES = List.of("ghp_", "gho_", "ghu_", "ghs_", "ghr_");
    private static final List<String> AWS_PREFIXES = List.of("AKIA", "ASIA", "AIDA", "AGPA", "AROA", "ANPA");
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "passwd", "api_key", "apikey", "secret", "token",
            "private_key", "access_key");
    private static final List<String> SENSITIVE_QUERY_KEYS = List.of(
            "password", "passwd", "token", "api_key", "apikey", "secret", "authorization",
            "cookie", "signature", "credential", "access_key", "private_key");

    private SecretRedactor() { }

    static String mask(String text) {
        StringBuilder masked = null;
        int copied = 0;
        for (int index = 0; index < text.length();) {
            Span span = secretAt(text, index);
            if (span == null) {
                index++;
                continue;
            }
            if (masked == null) masked = new StringBuilder(text.length());
            masked.append(text, copied, span.prefixEnd()).append("[redacted]");
            index = span.prefixEnd() + span.valueLength();
            copied = index;
        }
        return masked == null ? text : masked.append(text, copied, text.length()).toString();
    }

    static String arguments(ObjectMapper json, String toolName, String raw) {
        try {
            JsonNode parsed = json.readTree(raw);
            if (parsed == null || !parsed.isObject()) return mask(raw);
            redactNode(parsed, toolName);
            return json.writeValueAsString(parsed);
        } catch (Exception invalid) {
            return mask(raw);
        }
    }

    static String url(String raw) {
        StringBuilder output = new StringBuilder(raw.length());
        int scheme = raw.indexOf("://");
        int copied = 0;
        int querySearch = 0;
        if (scheme >= 0) {
            int authorityStart = scheme + 3;
            int authorityEnd = first(raw, authorityStart, "/?#");
            int at = raw.indexOf('@', authorityStart);
            if (at >= authorityStart && at < authorityEnd) {
                output.append(raw, 0, authorityStart).append("[redacted]@");
                output.append(raw, at + 1, authorityEnd);
                copied = authorityEnd;
            }
            querySearch = authorityEnd;
        }
        int query = raw.indexOf('?', querySearch);
        if (query < 0) return output.isEmpty() ? raw : output.append(raw, copied, raw.length()).toString();
        output.append(raw, copied, query + 1);
        int queryEnd = raw.indexOf('#', query + 1);
        if (queryEnd < 0) queryEnd = raw.length();
        int segment = query + 1;
        while (segment < queryEnd) {
            int end = raw.indexOf('&', segment);
            if (end < 0 || end > queryEnd) end = queryEnd;
            int equals = raw.indexOf('=', segment);
            if (equals < 0 || equals > end) equals = end;
            output.append(raw, segment, equals);
            if (equals < end) {
                output.append('=').append(sensitiveQueryKey(raw.substring(segment, equals))
                        ? "[redacted]" : raw.substring(equals + 1, end));
            }
            if (end < queryEnd) output.append('&');
            segment = end + 1;
        }
        return output.append(raw, queryEnd, raw.length()).toString();
    }

    private static void redactNode(JsonNode node, String toolName) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyStream().map(java.util.Map.Entry::getKey).toList())) {
                JsonNode value = object.get(name);
                if (sensitiveKey(name)) {
                    object.put(name, "[redacted]");
                } else if (value.isTextual()) {
                    object.put(name, toolName.equals("web_fetch") && name.equals("url")
                            ? url(value.asText()) : mask(value.asText()));
                } else {
                    redactNode(value, toolName);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (value.isTextual()) array.set(index, array.textNode(mask(value.asText())));
                else redactNode(value, toolName);
            }
        }
    }

    private static Span secretAt(String text, int start) {
        Span span = basicAuth(text, start);
        if (span != null) return span;
        span = awsAccessKey(text, start);
        if (span != null) return span;
        span = sensitiveAssignment(text, start);
        if (span != null) return span;
        span = inlineToken(text, start);
        return span != null ? span : githubToken(text, start);
    }

    private static Span basicAuth(String text, int start) {
        String prefix = "https://";
        if (!text.startsWith(prefix, start)) return null;
        int credentials = start + prefix.length();
        int colon = -1;
        for (int index = credentials; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n' || value == '\r' || Character.isWhitespace(value)
                    || value == '/' || value == '?' || value == '#') return null;
            if (value == ':' && colon < 0) colon = index;
            if (value == '@') return colon >= credentials && index > credentials
                    ? new Span(credentials, index - credentials) : null;
        }
        return null;
    }

    private static Span awsAccessKey(String text, int start) {
        if (start + 20 > text.length()) return null;
        if (!startsWithAny(text, start, AWS_PREFIXES) || start > 0 && tokenChar(text.charAt(start - 1))
                || start + 20 < text.length() && tokenChar(text.charAt(start + 20))) return null;
        for (int index = start; index < start + 20; index++) {
            char value = text.charAt(index);
            if (!(value >= 'A' && value <= 'Z') && !(value >= '0' && value <= '9')) return null;
        }
        return new Span(start, 20);
    }

    private static Span sensitiveAssignment(String text, int start) {
        if (start >= text.length() || start > 0 && assignmentKeyChar(text.charAt(start - 1))
                || !assignmentKeyChar(text.charAt(start))) return null;
        int equals = start;
        while (equals < text.length() && assignmentKeyChar(text.charAt(equals))) equals++;
        if (equals == start || equals >= text.length() || text.charAt(equals) != '='
                || !sensitiveKey(text.substring(start, equals))) return null;
        return assignmentValue(text, equals + 1);
    }

    private static Span assignmentValue(String text, int start) {
        if (start >= text.length()) return null;
        char first = text.charAt(start);
        if (first == '"' || first == '\'') {
            int content = start + 1;
            int end = content;
            while (end < text.length() && text.charAt(end) != '\n'
                    && text.charAt(end) != '\r' && text.charAt(end) != first) end++;
            return end > content ? new Span(content, end - content) : null;
        }
        int end = start;
        while (end < text.length()) {
            char value = text.charAt(end);
            if (value == '\n' || value == '\r' || Character.isWhitespace(value)
                    || value == '"' || value == '\'') break;
            end++;
        }
        return end > start ? new Span(start, end - start) : null;
    }

    private static Span inlineToken(String text, int start) {
        if (start > 0 && tokenChar(text.charAt(start - 1))) return null;
        for (String prefix : INLINE_PREFIXES) {
            if (!text.startsWith(prefix, start) || start + prefix.length() >= text.length()) continue;
            int end = start + prefix.length();
            while (end < text.length() && tokenChar(text.charAt(end))) end++;
            if (end - start >= 16) return new Span(start, end - start);
        }
        return null;
    }

    private static Span githubToken(String text, int start) {
        if (start + 40 > text.length() || !startsWithAny(text, start, GITHUB_PREFIXES)) return null;
        int end = start + 4;
        while (end < text.length() && tokenChar(text.charAt(end))) end++;
        return end - start >= 40 ? new Span(start, end - start) : null;
    }

    private static boolean sensitiveKey(String value) {
        String key = value.toLowerCase(Locale.ROOT);
        for (String candidate : SENSITIVE_KEYS) if (key.contains(candidate)) return true;
        return false;
    }

    private static boolean sensitiveQueryKey(String value) {
        String key = percentDecode(value).toLowerCase(Locale.ROOT);
        if (key.equals("sig")) return true;
        for (String candidate : SENSITIVE_QUERY_KEYS) if (key.contains(candidate)) return true;
        return false;
    }

    private static String percentDecode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    decoded.append((char) (high * 16 + low));
                    index += 2;
                    continue;
                }
            }
            decoded.append(value.charAt(index));
        }
        return decoded.toString();
    }

    private static int first(String value, int start, String delimiters) {
        int result = value.length();
        for (int index = start; index < value.length(); index++) {
            if (delimiters.indexOf(value.charAt(index)) >= 0) return index;
        }
        return result;
    }

    private static boolean startsWithAny(String value, int start, List<String> prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix, start)) return true;
        return false;
    }

    private static boolean assignmentKeyChar(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9' || value == '_';
    }

    private static boolean tokenChar(char value) {
        return assignmentKeyChar(value) || value == '-' || value == '.';
    }

    private record Span(int prefixEnd, int valueLength) { }
}
