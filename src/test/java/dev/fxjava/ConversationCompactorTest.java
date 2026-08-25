package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** fx-shaped compacted_summary rebuild: folding, thresholds, and bounded transcripts. */
class ConversationCompactorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rebuildProducesSummaryItemPlusThreeVerbatimExchanges() throws Exception {
        ArrayNode input = conversation(4);

        assertTrue(ConversationCompactor.eligible(input));
        ArrayNode rebuilt = ConversationCompactor.rebuild(json, input, "Dense summary text");

        assertEquals(1, rebuilt.size() - (input.size() - keepFrom(input)),
                "rebuilt length is summary plus kept tail");
        JsonNode summary = rebuilt.get(0);
        assertEquals("compacted_summary", summary.path("type").asText());
        assertEquals("Dense summary text", summary.path("summary_text").asText());
        assertEquals(1, summary.path("compaction_count").asInt());
        assertEquals(keepFrom(input), summary.path("removed_item_count").asInt());

        int keptExchanges = 0;
        for (int index = 1; index < rebuilt.size(); index++) {
            assertEquals(input.get(keepFrom(input) + index - 1), rebuilt.get(index),
                    "kept items are verbatim deep copies");
            if (!rebuilt.get(index).has("type")
                    && rebuilt.get(index).path("role").asText().equals("user")) {
                keptExchanges++;
            }
        }
        assertEquals(ConversationCompactor.KEEP_EXCHANGES, keptExchanges);
        assertFalse(rebuilt.toString().contains("user 0"), "summarized prefix is gone");
    }

    @Test
    void toolCallAndOutputPairsStayInsideKeptExchanges() throws Exception {
        ArrayNode input = json.createArrayNode();
        input.addObject().put("role", "user").put("content", "fix it");
        ObjectNode call = input.addObject().put("type", "function_call")
                .put("call_id", "call-1").put("name", "write_file").put("arguments", "{\"path\":\"a\"}");
        input.addObject().put("type", "function_call_output").put("call_id", "call-1")
                .put("output", "wrote a");
        input.addObject().put("role", "assistant");
        for (int exchange = 2; exchange <= 4; exchange++) {
            input.addObject().put("role", "assistant").put("content",
                    "answer " + exchange);
            input.addObject().put("role", "user").put("content", "question " + exchange);
        }

        assertTrue(ConversationCompactor.eligible(input));
        String transcript = ConversationCompactor.renderTranscript(input);
        ArrayNode rebuilt = ConversationCompactor.rebuild(json, input, "summary");

        assertTrue(transcript.contains("[tool call] write_file"));
        assertTrue(transcript.contains("[tool result] wrote a"));
        boolean sawCall = false;
        for (int index = 1; index < rebuilt.size(); index++) {
            JsonNode item = rebuilt.get(index);
            if (item.path("type").asText().equals("function_call")) sawCall = true;
            if (item.path("type").asText().equals("function_call_output")) {
                assertTrue(sawCall, "outputs never appear without their calls in the kept tail");
            }
        }
        assertTrue(rebuilt.get(1).path("role").asText().equals("user"),
                "tail starts at the user message opening the third-from-last exchange");
    }

    @Test
    void recompactingFoldsPriorSummaryAndIncrementsTheCount() throws Exception {
        ArrayNode first = conversation(5);
        ArrayNode once = ConversationCompactor.rebuild(json, first, "First summary");
        assertEquals(1, once.get(0).path("compaction_count").asInt());

        // More turns arrive after the first compaction.
        ArrayNode grown = once.deepCopy();
        grown.addObject().put("role", "assistant").put("content", "later answer");
        grown.addObject().put("role", "user").put("content", "later question");

        assertTrue(ConversationCompactor.eligible(grown));
        String transcript = ConversationCompactor.renderTranscript(grown);
        assertTrue(transcript.startsWith("Prior summary (from an earlier compaction):\nFirst summary"),
                "prior summary text is folded into the summarizer transcript");

        ArrayNode twice = ConversationCompactor.rebuild(json, grown, "Folded second summary");
        JsonNode summary = twice.get(0);
        assertEquals(2, summary.path("compaction_count").asInt(), "count increments across folds");
        assertEquals("Folded second summary", summary.path("summary_text").asText());
        assertEquals(1, countSummaries(twice), "exactly one leading summary item exists");
        assertEquals(4 + 3, summary.path("removed_item_count").asInt(),
                "removed_item_count accumulates across folds like fx");
    }

    @Test
    void conversationsBelowThresholdsAreRefused() {
        ArrayNode tiny = json.createArrayNode();
        for (int index = 0; index < 5; index++) {
            tiny.addObject().put("role", index % 2 == 0 ? "user" : "assistant")
                    .put("content", "msg " + index);
        }
        assertFalse(ConversationCompactor.eligible(tiny));

        // Six items but only three exchanges: nothing removable.
        ArrayNode shallow = json.createArrayNode();
        for (int index = 0; index < 3; index++) {
            shallow.addObject().put("role", "user").put("content", "u" + index);
            shallow.addObject().put("role", "assistant").put("content", "a" + index);
        }
        assertFalse(ConversationCompactor.eligible(shallow));

        assertThrows(IOException.class, () -> ConversationCompactor.rebuild(json, tiny, "s"),
                "rebuild refuses ineligible conversations fail-closed");
    }

    @Test
    void oversizedTranscriptsDropOldestLinesUnderTheByteBudget() {
        StringBuilder huge = new StringBuilder();
        huge.append("{\"role\":\"user\",\"content\":\"").append("x".repeat(200_000)).append("\"}");
        ArrayNode input = null;
        try {
            input = (ArrayNode) json.readTree("[" + huge + "]");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (int index = 0; index < 6; index++) {
            input.addObject().put("role", "user").put("content", "tail question " + index);
            input.addObject().put("role", "assistant").put("content", "tail answer " + index);
        }

        String transcript = ConversationCompactor.renderTranscript(input);
        assertTrue(transcript.getBytes(StandardCharsets.UTF_8).length
                        <= ConversationCompactor.MAX_TRANSCRIPT_BYTES,
                "transcript stays inside its byte budget");
        assertTrue(transcript.contains("(older transcript lines omitted)"));
        assertTrue(transcript.contains("tail question 5"), "recent lines survive truncation");
        assertFalse(transcript.contains("x".repeat(1000)), "oldest content is dropped first");
    }

    @Test
    void oversizedTranscriptsKeepThePriorSummaryBlockWithinBudget() {
        ArrayNode input = json.createArrayNode();
        input.addObject().put("type", "compacted_summary")
                .put("summary_text", "PRIOR-SUMMARY-MARKER goals and open work")
                .put("compaction_count", 1).put("removed_item_count", 4);
        StringBuilder huge = new StringBuilder();
        huge.append("{\"role\":\"user\",\"content\":\"").append("x".repeat(200_000)).append("\"}");
        try {
            ArrayNode tail = (ArrayNode) json.readTree("[" + huge + "]");
            input.addAll(tail);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        for (int index = 0; index < 6; index++) {
            input.addObject().put("role", "user").put("content", "tail question " + index);
            input.addObject().put("role", "assistant").put("content", "tail answer " + index);
        }

        String transcript = ConversationCompactor.renderTranscript(input);

        assertTrue(transcript.getBytes(StandardCharsets.UTF_8).length
                        <= ConversationCompactor.MAX_TRANSCRIPT_BYTES,
                "transcript stays inside its byte budget");
        assertTrue(transcript.startsWith("Prior summary (from an earlier compaction):\n"
                + "PRIOR-SUMMARY-MARKER"), "prior summary is exempt from the byte budget");
        assertTrue(transcript.contains("(older transcript lines omitted)"));
        assertTrue(transcript.contains("tail question 5"), "recent lines survive truncation");
        assertFalse(transcript.contains("x".repeat(1000)), "oldest content is dropped first");
    }

    @Test
    void oversizedPriorSummariesAreTruncatedWithAnExplicitMarkerNotDropped() {
        ArrayNode input = json.createArrayNode();
        StringBuilder giant = new StringBuilder();
        while (giant.length() < 150_000) {
            int start = giant.length();
            giant.append("summary-line-").append(start).append('\n');
        }
        input.addObject().put("type", "compacted_summary")
                .put("summary_text", giant.toString())
                .put("compaction_count", 3).put("removed_item_count", 40);
        for (int index = 0; index < 6; index++) {
            input.addObject().put("role", "user").put("content", "tail question " + index);
            input.addObject().put("role", "assistant").put("content", "tail answer " + index);
        }

        String transcript = ConversationCompactor.renderTranscript(input);

        assertTrue(transcript.getBytes(StandardCharsets.UTF_8).length
                        <= ConversationCompactor.MAX_TRANSCRIPT_BYTES,
                "even an oversized prior summary keeps the transcript in budget");
        assertTrue(transcript.contains("...(prior summary truncated"),
                "the truncation is explicit, never silent");
        assertTrue(transcript.contains("summary-line-0"), "the summary head still reaches the summarizer");
        assertTrue(transcript.contains("Conversation transcript:"));
        assertTrue(transcript.contains("tail question 5"),
                "conversation lines keep their reserved room beside a huge summary");
    }

    @Test
    void projectionReplacesSummariesOnRequestCopiesLeavingDurableStateUntouched() {
        ArrayNode durable = json.createArrayNode();
        ObjectNode summary = durable.addObject();
        summary.put("type", "compacted_summary").put("summary_text", "earlier goals")
                .put("compaction_count", 1).put("removed_item_count", 4);
        durable.addObject().put("role", "assistant").put("content", "kept answer");

        ArrayNode wire = durable.deepCopy();
        // Defensive: a summary away from index 0 projects the same way.
        wire.addObject().put("type", "compacted_summary").put("summary_text", "late summary");
        ConversationCompactor.projectSummariesForWire(wire);

        assertEquals(3, wire.size());
        assertEquals("user", wire.get(0).path("role").asText());
        assertEquals("[Summary of earlier conversation]\nearlier goals",
                wire.get(0).path("content").asText());
        assertFalse(wire.get(0).has("type"), "projected messages carry no custom type");
        assertFalse(wire.get(0).has("compaction_count"));
        assertFalse(wire.toString().contains("compacted_summary"));
        assertEquals("[Summary of earlier conversation]\nlate summary",
                wire.get(2).path("content").asText());

        assertEquals("compacted_summary", durable.get(0).path("type").asText(),
                "durable state keeps the raw item");
        assertEquals(1, durable.get(0).path("compaction_count").asInt());
    }

    private ArrayNode conversation(int exchanges) {
        ArrayNode input = json.createArrayNode();
        for (int index = 0; index < exchanges; index++) {
            input.addObject().put("role", "user").put("content", "user " + index);
            input.addObject().put("role", "assistant").put("content", "assistant " + index);
        }
        return input;
    }

    private int keepFrom(ArrayNode input) {
        List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < input.size(); index++) {
            JsonNode item = input.get(index);
            if (!item.has("type") && item.path("role").asText().equals("user")) starts.add(index);
        }
        return starts.get(starts.size() - ConversationCompactor.KEEP_EXCHANGES);
    }

    private int countSummaries(ArrayNode input) {
        int summaries = 0;
        for (JsonNode item : input) {
            if (item.path("type").asText().equals("compacted_summary")) summaries++;
        }
        return summaries;
    }
}
