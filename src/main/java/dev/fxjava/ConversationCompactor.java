package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Conversation compaction ported from fx's compacted_summary history turns
 * (prompt_context.zig): one model-authored summary item replaces everything
 * except the most recent exchanges, which stay verbatim so the next turn has
 * live tool context. Re-compacting folds the previous summary text into the
 * new one and increments a compaction_count stored inside the summary item,
 * following fx naming: {"type":"compacted_summary","summary_text":...,
 * "compaction_count":N}. The summarization round-trip itself runs through
 * {@link Agent#summarize}, so it never appends to durable conversation state.
 */
final class ConversationCompactor {
    /** Refuse to compact conversations smaller than this many items. */
    static final int MIN_ITEMS = 6;
    /**
     * Recent user/assistant/tool exchanges (one exchange = one user message
     * plus its assistant reply and any tool calls/results) kept verbatim after
     * the summary. Three balances live context against the summary's savings.
     */
    static final int KEEP_EXCHANGES = 3;
    /** Upper bound on the transcript sent to the summarizer, in UTF-8 bytes. */
    static final int MAX_TRANSCRIPT_BYTES = 100 * 1024;
    /**
     * Bytes always reserved for conversation lines even when a prior summary
     * is enormous, so the summary can never crowd the transcript out entirely.
     */
    private static final int MIN_CONVERSATION_BUDGET = 2048;
    /** Slack held back when tail-trimming so the omission marker fits. */
    private static final int TRIM_SLACK_BYTES = 128;
    private static final String SUMMARY_HEADER = "Prior summary (from an earlier compaction):\n";
    private static final String SUMMARY_TRUNCATED_MARKER =
            "\n...(prior summary truncated to fit the summarizer budget)";
    private static final String TRANSCRIPT_OMITTED_MARKER = "(older transcript lines omitted)\n";

    static final String SUMMARIZER_INSTRUCTIONS =
            "You compress coding-agent conversations. Summarize the transcript as dense plain text "
                    + "covering: the user's goals, decisions made so far, open work, and key file paths. "
                    + "Fold any prior summary into your answer. Keep it under 1200 characters. No preamble.";

    private static final String SUMMARY_TYPE = "compacted_summary";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConversationCompactor() {
    }

    /**
     * fx treats compacted_summary as a history-layer concept only and projects
     * it into ordinary chat messages before any gateway call
     * (prompt_context.zig appendHistoryChatMessages); this mirrors that on a
     * request copy, never on durable state. Projected as role "user" rather
     * than "system": standing instructions already travel through the
     * Responses API's top-level instructions field, so the summary must not
     * pose as a second instruction channel. Defensive against summaries
     * appearing at any index, not just the head.
     */
    static void projectSummariesForWire(ArrayNode wire) {
        for (int index = 0; index < wire.size(); index++) {
            JsonNode item = wire.get(index);
            if (!item.path("type").asText().equals(SUMMARY_TYPE)) continue;
            ObjectNode message = JSON.createObjectNode();
            message.put("role", "user");
            message.put("content", "[Summary of earlier conversation]\n"
                    + item.path("summary_text").asText(""));
            wire.set(index, message);
        }
    }

    /** True when the conversation is big enough for compaction to remove anything. */
    static boolean eligible(ArrayNode input) {
        return input != null && input.size() >= MIN_ITEMS && keepFromIndex(input) > 0;
    }

    static ObjectNode buildSummaryItem(ObjectMapper json, String summaryText, ArrayNode originalInput) {
        ObjectNode summary = json.createObjectNode();
        summary.put("type", SUMMARY_TYPE);
        summary.put("summary_text", summaryText);
        summary.put("compaction_count", previousCompactionCount(originalInput) + 1);
        // fx accumulates removals across folds: prior removed items stay gone
        // even though they no longer appear in the input being compacted.
        summary.put("removed_item_count",
                previousRemovedItemCount(originalInput) + keepFromIndex(originalInput));
        return summary;
    }

    /** Summary plus the trailing {@link #KEEP_EXCHANGES} exchanges verbatim. */
    static ArrayNode rebuild(ObjectMapper json, ArrayNode input, String summaryText) throws IOException {
        if (!eligible(input)) throw new IOException("Conversation has nothing to compact");
        ArrayNode rebuilt = json.createArrayNode();
        rebuilt.add(buildSummaryItem(json, summaryText, input));
        int keepFrom = keepFromIndex(input);
        for (int index = keepFrom; index < input.size(); index++) {
            rebuilt.add(input.get(index).deepCopy());
        }
        return rebuilt;
    }

    /**
     * Bounded plain-text rendering of the conversation for the summarizer.
     * The prior-summary block, when one leads the history, is exempt from the
     * byte budget: only conversation lines are tail-trimmed against
     * {@link #MAX_TRANSCRIPT_BYTES} minus the summary block's own size, so an
     * oversized transcript can no longer silently cut the folded summary. A
     * summary too large for even the reserved room is truncated itself with an
     * explicit marker instead of disappearing.
     */
    static String renderTranscript(ArrayNode input) {
        boolean hasLeadingSummary = input.size() > 0
                && input.get(0).path("type").asText().equals(SUMMARY_TYPE);
        String summaryBlock = "";
        if (hasLeadingSummary) {
            summaryBlock = SUMMARY_HEADER + fitPriorSummary(input.get(0).path("summary_text").asText());
        }
        int summaryBytes = utf8Length(summaryBlock);

        StringBuilder conversation = new StringBuilder("Conversation transcript:\n");
        int start = hasLeadingSummary ? 1 : 0;
        for (int index = start; index < input.size(); index++) {
            conversation.append(renderItem(input.get(index))).append('\n');
        }
        String rendered = conversation.toString();
        int budget = MAX_TRANSCRIPT_BYTES - summaryBytes;
        if (utf8Length(rendered) <= budget) return summaryBlock + rendered;
        String[] lines = rendered.split("\n", -1);
        StringBuilder bounded = new StringBuilder();
        int kept = 0;
        int usable = budget - utf8Length(TRANSCRIPT_OMITTED_MARKER) - TRIM_SLACK_BYTES;
        for (int index = lines.length - 1; index >= 0; index--) {
            int lineBytes = utf8Length(lines[index]) + 1;
            if (kept + lineBytes > usable) break;
            kept += lineBytes;
            bounded.insert(0, lines[index] + "\n");
        }
        return summaryBlock + TRANSCRIPT_OMITTED_MARKER + bounded;
    }

    /** Prior summary text truncated, with an explicit marker, to its reserved budget. */
    private static String fitPriorSummary(String summaryText) {
        int allowance = MAX_TRANSCRIPT_BYTES - MIN_CONVERSATION_BUDGET
                - utf8Length(SUMMARY_HEADER) - utf8Length("\n\n");
        if (utf8Length(summaryText) <= allowance) return summaryText + "\n\n";
        return truncateToUtf8Bytes(summaryText, allowance - utf8Length(SUMMARY_TRUNCATED_MARKER))
                + SUMMARY_TRUNCATED_MARKER + "\n\n";
    }

    /** Cuts {@code value} to at most {@code maxBytes} UTF-8 bytes without splitting characters. */
    private static String truncateToUtf8Bytes(String value, int maxBytes) {
        StringBuilder cut = new StringBuilder();
        int used = 0;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int width = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (used + width > maxBytes) break;
            cut.appendCodePoint(codePoint);
            used += width;
            offset += Character.charCount(codePoint);
        }
        return cut.toString();
    }

    private static int utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** Count carried by the leading summary item, or zero before any compaction. */
    private static int previousCompactionCount(ArrayNode input) {
        if (input == null || input.size() == 0) return 0;
        JsonNode leading = input.get(0);
        if (!leading.path("type").asText().equals(SUMMARY_TYPE)) return 0;
        return Math.max(0, leading.path("compaction_count").asInt(0));
    }

    /** Items already removed by earlier folds, carried by the leading summary. */
    private static int previousRemovedItemCount(ArrayNode input) {
        if (input == null || input.size() == 0) return 0;
        JsonNode leading = input.get(0);
        if (!leading.path("type").asText().equals(SUMMARY_TYPE)) return 0;
        return Math.max(0, leading.path("removed_item_count").asInt(0));
    }

    /**
     * Index where the verbatim tail begins: the user message opening the
     * K-th-from-last exchange. An exchange starts at a bare user message and
     * spans its assistant reply plus tool calls/results, so call/output pairs
     * never split. Zero when there is nothing removable.
     */
    private static int keepFromIndex(ArrayNode input) {
        int userMessages = 0;
        for (int index = 0; index < input.size(); index++) {
            JsonNode item = input.get(index);
            if (!item.has("type") && item.path("role").asText().equals("user")) userMessages++;
        }
        if (userMessages <= KEEP_EXCHANGES) return 0;
        int target = userMessages - KEEP_EXCHANGES + 1;
        int seen = 0;
        for (int index = 0; index < input.size(); index++) {
            JsonNode item = input.get(index);
            if (!item.has("type") && item.path("role").asText().equals("user") && ++seen == target) {
                return index;
            }
        }
        return 0;
    }

    private static String renderItem(JsonNode item) {
        String type = item.path("type").asText("");
        switch (type) {
            case "message": {
                String role = item.path("role").asText("assistant");
                return "[" + role + "] " + messageText(item);
            }
            case "function_call":
                return "[tool call] " + item.path("name").asText() + " " + item.path("arguments").asText("{}");
            case "function_call_output":
                return "[tool result] " + item.path("output").asText("");
            default:
                return roleLine(item);
        }
    }

    private static String roleLine(JsonNode item) {
        String role = item.path("role").asText("");
        if (role.isEmpty()) return "[" + item.path("type").asText("item") + "] "
                + item.path("content").asText("");
        return "[" + role + "] " + contentText(item.path("content"));
    }

    private static String messageText(JsonNode message) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : message.path("content")) {
            String value = content.path("type").asText().equals("refusal")
                    ? content.path("refusal").asText()
                    : content.path("text").asText();
            if (!value.isEmpty()) {
                if (text.length() > 0) text.append(' ');
                text.append(value);
            }
        }
        return text.toString();
    }

    private static String contentText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        return messageText(content);
    }
}
