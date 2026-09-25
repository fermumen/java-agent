package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Conservative estimate for the serialized request context. The defaults are
 * an explicit operating threshold, not a claim about any provider model's
 * maximum window. Callers may supply another threshold and fixed request
 * overhead when constructing or using the policy.
 */
final class ContextBudget {
    static final int DEFAULT_REQUEST_TOKEN_BUDGET = 96_000;
    static final int DEFAULT_TRIGGER_PERCENT = 80;
    static final int DEFAULT_IMAGE_TOKEN_RESERVE = 4_096;

    private final int requestTokenBudget;
    private final int triggerPercent;
    private final int imageTokenReserve;

    ContextBudget() {
        this(DEFAULT_REQUEST_TOKEN_BUDGET, DEFAULT_TRIGGER_PERCENT, DEFAULT_IMAGE_TOKEN_RESERVE);
    }

    ContextBudget(int requestTokenBudget, int triggerPercent, int imageTokenReserve) {
        if (requestTokenBudget < 1) throw new IllegalArgumentException("request token budget must be positive");
        if (triggerPercent < 1 || triggerPercent > 100) {
            throw new IllegalArgumentException("context trigger percent must be between 1 and 100");
        }
        if (imageTokenReserve < 0) throw new IllegalArgumentException("image token reserve must not be negative");
        this.requestTokenBudget = requestTokenBudget;
        this.triggerPercent = triggerPercent;
        this.imageTokenReserve = imageTokenReserve;
    }

    int requestTokenBudget() { return requestTokenBudget; }

    int triggerPercent() { return triggerPercent; }

    int imageTokenReserve() { return imageTokenReserve; }

    long triggerTokenBudget() {
        return Math.max(1L, (long) requestTokenBudget * triggerPercent / 100L);
    }

    /** Maximum history estimate left after accounting for fixed prompt overhead. */
    long historyBudget(long fixedRequestTokens) {
        requireNonNegative(fixedRequestTokens);
        return Math.max(0L, triggerTokenBudget() - fixedRequestTokens);
    }

    long estimateHistoryTokens(ArrayNode history) {
        return estimateTokens(history);
    }

    /**
     * Estimates structured request data without charging base64 image bytes as
     * text. Each input_image receives the configured fixed reserve instead.
     */
    long estimateTokens(JsonNode value) {
        return estimateNode(value);
    }

    long estimateTextTokens(String text) {
        return textTokens(text);
    }

    boolean shouldCompact(ArrayNode history) {
        return shouldCompact(history, 0);
    }

    /** The caller supplies estimated instructions, tool schemas, and parent context here. */
    boolean shouldCompact(ArrayNode history, long fixedRequestTokens) {
        requireNonNegative(fixedRequestTokens);
        return addSaturated(estimateHistoryTokens(history), fixedRequestTokens) >= triggerTokenBudget();
    }

    boolean fits(ArrayNode history, long fixedRequestTokens) {
        requireNonNegative(fixedRequestTokens);
        return addSaturated(estimateHistoryTokens(history), fixedRequestTokens) <= triggerTokenBudget();
    }

    private long estimateNode(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (value.isTextual()) return textTokens(value.asText());
        if (value.isNumber() || value.isBoolean()) return 1;
        if (value.isArray()) {
            long total = 2;
            for (JsonNode child : value) total = addSaturated(total, addSaturated(1, estimateNode(child)));
            return total;
        }
        if (value.isObject()) {
            if (value.path("type").asText().equals("input_image")) {
                // image_url can be a multi-megabyte data URL; never inspect it.
                return addSaturated(6L, imageTokenReserve);
            }
            long total = 4;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                total = addSaturated(total, addSaturated(1, textTokens(field.getKey())));
                total = addSaturated(total, estimateNode(field.getValue()));
            }
            return total;
        }
        return 1;
    }

    private static long textTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1L, (bytes + 2L) / 3L);
    }

    private static long addSaturated(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void requireNonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("fixed request token estimate must not be negative");
    }
}
