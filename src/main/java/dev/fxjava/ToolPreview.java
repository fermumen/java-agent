package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Safe, inspectable tool details for approval and progress displays. */
final class ToolPreview {
    private ToolPreview() { }

    static String safeText(String value) {
        String masked = SecretRedactor.mask(value == null ? "" : value);
        StringBuilder safe = new StringBuilder(masked.length());
        for (int index = 0; index < masked.length();) {
            int codePoint = masked.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') safe.append("\\n");
            else if (codePoint == '\r') safe.append("\\r");
            else if (codePoint == '\t') safe.append("\\t");
            else if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                safe.append("\\u{").append(Integer.toHexString(codePoint)).append('}');
            } else {
                safe.appendCodePoint(codePoint);
            }
        }
        return safe.toString();
    }

    static String redactedJson(ObjectMapper json, String toolName, JsonNode arguments) {
        try {
            String raw = json.writeValueAsString(arguments);
            return safeText(SecretRedactor.arguments(json, toolName, raw));
        } catch (Exception invalid) {
            return safeText(String.valueOf(arguments));
        }
    }
}
