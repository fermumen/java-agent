package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

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
                    + "preserving the user's original objective, constraints, acceptance criteria, and "
                    + "important instructions, along with decisions, open work, tool results, and key paths. "
                    + "Never replace a specific user requirement with a vague paraphrase. Fold any prior "
                    + "summary into your answer. Keep it under 1200 characters. No preamble.";

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
            StringBuilder content = new StringBuilder("[Summary of earlier conversation]\n")
                    .append(item.path("summary_text").asText(""));
            String original = item.path("original_user_context").asText("");
            if (!original.isEmpty()) {
                content.append("\n\n[Original user request retained verbatim]\n").append(original);
            }
            message.put("content", content.toString());
            wire.set(index, message);
        }
    }

    /** True when the conversation is big enough for compaction to remove anything. */
    static boolean eligible(ArrayNode input) {
        return input != null && input.size() >= MIN_ITEMS && keepFromIndex(input) > 0;
    }

    /** Manual /compact can also help a history that is over budget inside a few long exchanges. */
    static boolean eligible(ArrayNode input, ContextBudget budget, long fixedRequestTokens) {
        return eligible(input) || (input != null && input.size() > 1
                && budget.shouldCompact(input, fixedRequestTokens));
    }

    static ObjectNode buildSummaryItem(ObjectMapper json, String summaryText, ArrayNode originalInput) {
        return buildSummaryItem(json, summaryText, originalInput, keepFromIndex(originalInput));
    }

    private static ObjectNode buildSummaryItem(ObjectMapper json, String summaryText,
                                               ArrayNode originalInput, int removedItemCount) {
        ObjectNode summary = json.createObjectNode();
        summary.put("type", SUMMARY_TYPE);
        summary.put("summary_text", summaryText);
        String originalUserContext = originalUserContext(originalInput);
        if (!originalUserContext.isEmpty()) summary.put("original_user_context", originalUserContext);
        summary.put("compaction_count", previousCompactionCount(originalInput) + 1);
        // fx accumulates removals across folds: prior removed items stay gone
        // even though they no longer appear in the input being compacted.
        summary.put("removed_item_count",
                previousRemovedItemCount(originalInput) + Math.max(0, removedItemCount));
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
     * Rebuilds history to fit the configured request budget. It first keeps
     * the normal three-exchange tail, then advances to later safe boundaries
     * when needed. The latest user message always stays verbatim, the original
     * user request stays in the summary item, and a tool call is never retained
     * without all of its matching outputs (or vice versa).
     */
    static ArrayNode rebuildForBudget(ObjectMapper json, ArrayNode input, String summaryText,
                                      ContextBudget budget, long fixedRequestTokens) throws IOException {
        if (input == null || input.isEmpty()) throw new IOException("Conversation has nothing to compact");
        if (summaryText == null || summaryText.strip().isEmpty()) {
            throw new IOException("The model returned an empty summary; conversation left untouched");
        }
        if (fixedRequestTokens < 0) throw new IllegalArgumentException("fixed request estimate must not be negative");

        int latestUser = latestUserIndex(input);
        if (latestUser < 0) {
            throw new IOException("cannot safely compact a history without a user request; conversation left untouched");
        }
        ObjectNode summary = buildSummaryItem(json, summaryText.strip(), input);
        boolean[] safeBoundaries = safeBoundaries(input);
        int preferredStart = keepFromIndex(input);

        for (int start = preferredStart; start <= input.size(); start++) {
            if (!safeBoundaries[start]) continue;
            ArrayNode candidate = json.createArrayNode();
            candidate.add(summary.deepCopy());
            int retainedCount = 0;
            for (int index = 0; index < input.size(); index++) {
                JsonNode item = input.get(index);
                if (item.path("type").asText().equals(SUMMARY_TYPE)) continue;
                if (index < start && index != latestUser) continue;
                candidate.add(item.deepCopy());
                retainedCount++;
            }
            if (!toolPairsIntact(candidate)) continue;
            if (retainedCount >= input.size()) continue;
            ObjectNode countedSummary = buildSummaryItem(json, summaryText.strip(), input,
                    Math.max(0, input.size() - retainedCount));
            candidate.set(0, countedSummary);
            if (budget.fits(candidate, fixedRequestTokens)) return candidate;
        }
        throw new IOException("cannot safely compact: the summary and minimum retained user context "
                + "exceed the configured context budget; conversation left untouched");
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
    static String renderTranscript(ArrayNode input) throws IOException {
        return renderTranscript(input, null, 0);
    }

    /** Renders under both the summarizer byte cap and the configured request-token threshold. */
    static String renderTranscript(ArrayNode input, ContextBudget budget,
                                   long fixedRequestTokens) throws IOException {
        if (fixedRequestTokens < 0) throw new IllegalArgumentException("fixed request estimate must not be negative");
        long maxTranscriptTokens = budget == null ? Long.MAX_VALUE : budget.historyBudget(fixedRequestTokens);
        int maxTranscriptBytes = MAX_TRANSCRIPT_BYTES;
        if (budget != null) {
            long tokenBytes = maxTranscriptTokens > Integer.MAX_VALUE / 3L
                    ? Integer.MAX_VALUE : maxTranscriptTokens * 3L;
            maxTranscriptBytes = (int) Math.min(MAX_TRANSCRIPT_BYTES, tokenBytes);
        }
        int minConversationBytes = Math.min(MIN_CONVERSATION_BUDGET,
                Math.max(64, maxTranscriptBytes / 10));
        boolean hasLeadingSummary = input.size() > 0
                && input.get(0).path("type").asText().equals(SUMMARY_TYPE);
        String originalUser = originalUserContext(input);
        String latestUser = latestUserContext(input);
        StringBuilder pinned = new StringBuilder();
        if (!originalUser.isEmpty()) {
            pinned.append("Original user request and constraints (retained verbatim):\n")
                    .append(originalUser).append("\n\n");
        }
        if (!latestUser.isEmpty() && !latestUser.equals(originalUser)) {
            pinned.append("Latest user request (retained verbatim):\n")
                    .append(latestUser).append("\n\n");
        }
        String summaryBlock = hasLeadingSummary
                ? SUMMARY_HEADER + fitPriorSummary(input.get(0).path("summary_text").asText(),
                utf8Length(pinned.toString()) + utf8Length("Conversation transcript:\n"),
                maxTranscriptBytes, minConversationBytes)
                : "";
        String prefix = summaryBlock + pinned + "Conversation transcript:\n";
        int prefixBytes = utf8Length(prefix);
        if (prefixBytes + minConversationBytes > maxTranscriptBytes) {
            throw new IOException("cannot safely summarize: the retained original and latest user requests "
                    + "do not fit the summarizer transcript budget; conversation left untouched");
        }
        if (budget != null && budget.estimateTextTokens(prefix) + fixedRequestTokens + 6
                > budget.triggerTokenBudget()) {
            throw new IOException("cannot safely summarize: the retained original and latest user requests "
                    + "do not fit the configured summarizer request budget; conversation left untouched");
        }

        StringBuilder conversation = new StringBuilder();
        int start = hasLeadingSummary ? 1 : 0;
        for (int index = start; index < input.size(); index++) {
            conversation.append(renderItem(input.get(index))).append('\n');
        }
        String rendered = conversation.toString();
        int byteBudget = maxTranscriptBytes - prefixBytes;
        String full = prefix + rendered;
        if (utf8Length(rendered) <= byteBudget
                && transcriptFits(full, budget, fixedRequestTokens)) return full;
        String[] lines = rendered.split("\n", -1);
        java.util.List<String> keptLines = new java.util.ArrayList<>();
        int keptBytes = 0;
        long keptTokens = 0;
        String markedPrefix = prefix + TRANSCRIPT_OMITTED_MARKER;
        int usableBytes = byteBudget - utf8Length(TRANSCRIPT_OMITTED_MARKER) - TRIM_SLACK_BYTES;
        long usableTokens = budget == null ? Long.MAX_VALUE
                : budget.triggerTokenBudget() - fixedRequestTokens
                - budget.estimateTextTokens(markedPrefix) - 6;
        if (usableBytes < 0 || usableTokens < 0) {
            throw new IOException("cannot safely summarize: the retained user context leaves no room "
                    + "for a transcript; conversation left untouched");
        }
        for (int index = lines.length - 1; index >= 0; index--) {
            int lineBytes = utf8Length(lines[index]) + 1;
            long lineTokens = budget == null ? 0 : budget.estimateTextTokens(lines[index] + "\n") + 1;
            if (keptBytes + lineBytes > usableBytes || keptTokens + lineTokens > usableTokens) break;
            keptBytes += lineBytes;
            keptTokens += lineTokens;
            keptLines.add(lines[index]);
        }
        StringBuilder bounded = new StringBuilder();
        for (int index = keptLines.size() - 1; index >= 0; index--) {
            bounded.append(keptLines.get(index)).append('\n');
        }
        String result = markedPrefix + bounded;
        if (!transcriptFits(result, budget, fixedRequestTokens)) {
            throw new IOException("cannot safely summarize: the bounded transcript exceeds the configured "
                    + "summarizer request budget; conversation left untouched");
        }
        return result;
    }

    /** Prior summary text truncated, with an explicit marker, to its reserved budget. */
    private static String fitPriorSummary(String summaryText, int otherReservedBytes,
                                          int maxTranscriptBytes, int minConversationBytes) throws IOException {
        int allowance = maxTranscriptBytes - minConversationBytes - otherReservedBytes
                - utf8Length(SUMMARY_HEADER) - utf8Length("\n\n");
        if (allowance < 0) {
            throw new IOException("cannot safely summarize: the previous summary and retained user requests "
                    + "do not fit the summarizer transcript budget; conversation left untouched");
        }
        if (utf8Length(summaryText) <= allowance) return summaryText + "\n\n";
        return truncateToUtf8Bytes(summaryText, allowance - utf8Length(SUMMARY_TRUNCATED_MARKER))
                + SUMMARY_TRUNCATED_MARKER + "\n\n";
    }

    private static boolean transcriptFits(String transcript, ContextBudget budget,
                                          long fixedRequestTokens) {
        return budget == null || budget.estimateTextTokens(transcript) + fixedRequestTokens + 6
                <= budget.triggerTokenBudget();
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

    private static int latestUserIndex(ArrayNode input) {
        for (int index = input.size() - 1; index >= 0; index--) {
            JsonNode item = input.get(index);
            if (!item.has("type") && item.path("role").asText().equals("user")) return index;
        }
        return -1;
    }

    private static String originalUserContext(ArrayNode input) {
        if (input == null || input.isEmpty()) return "";
        JsonNode leading = input.get(0);
        if (leading.path("type").asText().equals(SUMMARY_TYPE)) {
            String carried = leading.path("original_user_context").asText("");
            if (!carried.isEmpty()) return carried;
        }
        for (JsonNode item : input) {
            if (!item.has("type") && item.path("role").asText().equals("user")) {
                return contentText(item.path("content"));
            }
        }
        return "";
    }

    private static String latestUserContext(ArrayNode input) {
        int index = latestUserIndex(input);
        return index < 0 ? "" : contentText(input.get(index).path("content"));
    }

    /**
     * A safe suffix may start between unrelated items, but never within a
     * contiguous call batch, between its reasoning item and calls, or between
     * any call and its matching output.
     */
    private static boolean[] safeBoundaries(ArrayNode input) {
        boolean[] safe = new boolean[input.size() + 1];
        java.util.Arrays.fill(safe, true);
        for (int index = 0; index < input.size(); index++) {
            if (!input.get(index).path("type").asText().equals("function_call")) continue;
            int callStart = index;
            while (callStart > 0
                    && input.get(callStart - 1).path("type").asText().equals("function_call")) {
                callStart--;
            }
            while (callStart > 0 && assistantPrelude(input.get(callStart - 1))) {
                callStart--;
            }
            int callEnd = index;
            java.util.Set<String> ids = new java.util.HashSet<>();
            while (callEnd < input.size()
                    && input.get(callEnd).path("type").asText().equals("function_call")) {
                String id = input.get(callEnd).path("call_id").asText("");
                if (!id.isEmpty()) ids.add(id);
                callEnd++;
            }
            int outputEnd = callEnd - 1;
            for (int outputIndex = callEnd; outputIndex < input.size(); outputIndex++) {
                JsonNode output = input.get(outputIndex);
                if (output.path("type").asText().equals("function_call_output")
                        && ids.contains(output.path("call_id").asText(""))) {
                    outputEnd = Math.max(outputEnd, outputIndex);
                }
            }
            for (int boundary = callStart + 1; boundary <= outputEnd; boundary++) {
                safe[boundary] = false;
            }
        }
        return safe;
    }

    private static boolean assistantPrelude(JsonNode item) {
        String type = item.path("type").asText();
        return type.equals("reasoning") || (type.equals("message")
                && item.path("role").asText().equals("assistant"));
    }

    private static boolean toolPairsIntact(ArrayNode candidate) {
        java.util.Map<String, Integer> calls = new java.util.HashMap<>();
        java.util.Map<String, Integer> outputs = new java.util.HashMap<>();
        for (JsonNode item : candidate) {
            String type = item.path("type").asText();
            String id = item.path("call_id").asText("");
            if (type.equals("function_call")) {
                if (id.isEmpty()) return false;
                calls.put(id, calls.getOrDefault(id, 0) + 1);
            }
            if (type.equals("function_call_output")) {
                if (id.isEmpty()) return false;
                outputs.put(id, outputs.getOrDefault(id, 0) + 1);
            }
        }
        for (Map.Entry<String, Integer> call : calls.entrySet()) {
            if (!call.getValue().equals(outputs.get(call.getKey()))) return false;
        }
        for (Map.Entry<String, Integer> output : outputs.entrySet()) {
            if (!output.getValue().equals(calls.get(output.getKey()))) return false;
        }
        return true;
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
        return contentText(message.path("content"));
    }

    private static void appendPart(StringBuilder text, String value) {
        if (!value.isEmpty()) {
            if (text.length() > 0) text.append(' ');
            text.append(value);
        }
    }

    /**
     * Summarizer-facing placeholder for one image part. Base64 data never
     * enters the transcript: the label is the cheaply available media subtype
     * from a data URL or sidecar reference filename, else generic.
     */
    private static String imagePlaceholder(JsonNode part) {
        String url = part.path("image_url").asText();
        if (url.startsWith("data:image/")) {
            int end = url.indexOf(';');
            if (end > "data:image/".length()) return "<image "
                    + url.substring("data:image/".length(), end) + ">";
        } else if (url.startsWith("java-agent-image:")) {
            String filename = url.substring("java-agent-image:".length());
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) {
                String extension = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                switch (extension) {
                    case "png":
                    case "jpg":
                    case "gif":
                        return "<image " + extension + ">";
                    case "webp":
                        return "<image webp>";
                    default:
                        break;
                }
            }
        }
        return "<image>";
    }

    private static String contentText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isObject() && part.path("type").asText().equals("input_image")) {
                    appendPart(text, imagePlaceholder(part));
                    continue;
                }
                String value = part.path("type").asText().equals("refusal")
                        ? part.path("refusal").asText()
                        : part.path("text").asText();
                appendPart(text, value);
            }
            return text.toString();
        }
        if (content.isObject()) return messageText(content);
        return "";
    }
}
