package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Cumulative per-session token usage, embedded in the session snapshot beside
 * permission state and shaped after fx's usage records: input_tokens plus
 * output_tokens parsed from Responses completed payloads. Snapshots written
 * before usage tracking carry no {@code usage_state} field; those load as
 * zeroed state and gain explicit state when next saved. A present field,
 * including an explicit JSON null, that is unreadable or invalid fails closed
 * instead of silently dropping totals.
 */
final class SessionUsage {
    static final int SCHEMA_VERSION = 1;
    private static final SessionUsage ZEROED = new SessionUsage(0, 0);

    private final long inputTokens;
    private final long outputTokens;

    private SessionUsage(long inputTokens, long outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    static SessionUsage zeroed() {
        return ZEROED;
    }

    long inputTokens() {
        return inputTokens;
    }

    long outputTokens() {
        return outputTokens;
    }

    /** Saturating add so absurd provider numbers cannot wrap totals negative. */
    SessionUsage added(long inputDelta, long outputDelta) {
        if (inputDelta == 0 && outputDelta == 0) return this;
        return new SessionUsage(saturatingAdd(inputTokens, inputDelta),
                saturatingAdd(outputTokens, outputDelta));
    }

    ObjectNode encode(ObjectMapper mapper) {
        ObjectNode encoded = mapper.createObjectNode();
        encoded.put("schema_version", SCHEMA_VERSION);
        encoded.put("input_tokens", inputTokens);
        encoded.put("output_tokens", outputTokens);
        return encoded;
    }

    /** Decodes persisted usage state; unreadable or invalid shapes are errors. */
    static SessionUsage decode(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("Invalid session usage state");
        JsonNode schema = node.get("schema_version");
        if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()
                || schema.intValue() != SCHEMA_VERSION) {
            throw new IOException("Unsupported session usage schema");
        }
        long input = requiredTokens(node, "input_tokens");
        long output = requiredTokens(node, "output_tokens");
        return input == 0 && output == 0 ? ZEROED : new SessionUsage(input, output);
    }

    private static long requiredTokens(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw new IOException("Invalid session usage field: " + field);
        }
        return value.longValue();
    }

    private static long saturatingAdd(long base, long delta) {
        if (delta <= 0) return base;
        long total = base + delta;
        return total < 0 ? Long.MAX_VALUE : total;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SessionUsage)) return false;
        SessionUsage that = (SessionUsage) other;
        return inputTokens == that.inputTokens && outputTokens == that.outputTokens;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(inputTokens);
        return 31 * result + Long.hashCode(outputTokens);
    }

    @Override
    public String toString() {
        return "SessionUsage[input=" + inputTokens + ", output=" + outputTokens + "]";
    }
}
