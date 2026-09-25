package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Automatic budget checks at request boundaries, including durable checkpoints. */
class AutomaticContextManagementTest {
    private static final String OLD_RESULT = "old-result-" + "x".repeat(2_400);
    private static final String RECENT_RESULT = "recent-result";
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void budgetCrossingMidToolLoopIncludesFixedOverheadAndPersistsOnePairedSummary() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        String instructions = "system instruction ".repeat(90);
        LoopTool tool = new LoopTool();
        ContextBudget estimator = new ContextBudget(10_000, 100, 0);
        long fixedTokens = fixedTokens(estimator, instructions, tool);
        ArrayNode historyAfterFirstTool = seededHistory("ORIGINAL_TASK: preserve the initial objective.",
                "old answer", "CURRENT_TASK: complete the current change.", 1, OLD_RESULT);
        ArrayNode historyAfterSecondTool = historyAfterFirstTool.deepCopy();
        appendToolRound(historyAfterSecondTool, 2, RECENT_RESULT);
        long beforeSecondRequest = estimator.estimateHistoryTokens(historyAfterFirstTool);
        long afterSecondRequest = estimator.estimateHistoryTokens(historyAfterSecondTool);
        long addition = afterSecondRequest - beforeSecondRequest;
        assertTrue(addition > 2, "the second completed tool round adds measurable context");

        int trigger = Math.toIntExact(fixedTokens + beforeSecondRequest + Math.max(1, addition / 2));
        ContextBudget budget = new ContextBudget(trigger, 100, 0);
        assertFalse(budget.shouldCompact(historyAfterFirstTool, fixedTokens),
                "instruction and tool-schema overhead still leaves the first tool result under budget");
        assertTrue(budget.shouldCompact(historyAfterSecondTool, fixedTokens),
                "the second completed tool round crosses the request threshold");

        ArrayNode dryRun = ConversationCompactor.rebuildForBudget(json, historyAfterSecondTool,
                "summary", budget, fixedTokens);
        assertTrue(hasPair(dryRun, "call-2"), "the most recent complete tool group remains verbatim");
        assertFalse(hasAnyToolRecord(dryRun, "call-1"), "the older large tool result is folded into the summary");

        ScriptedClient client = new ScriptedClient();
        Agent agent = agent(client, instructions, tool, budget);
        SessionRuntime session = SessionRuntime.start(agent, new SessionStore(json, state), workspace,
                "model", instructions, null);
        session.prompt("ORIGINAL_TASK: preserve the initial objective.");
        session.prompt("CURRENT_TASK: complete the current change.");

        assertEquals(1, client.summaryRequests, "one summary request runs at the budget crossing");
        assertEquals(5, client.requests.size(), "initial answer, two tool steps, one summary, and final request");
        ArrayNode finalRequest = client.requests.get(4).input;
        assertTrue(finalRequest.toString().contains("ORIGINAL_TASK: preserve the initial objective."));
        assertTrue(finalRequest.toString().contains("CURRENT_TASK: complete the current change."));
        assertTrue(hasPair(finalRequest, "call-2"));
        assertFalse(hasAnyToolRecord(finalRequest, "call-1"));
        assertEquals(1, countSummaries(session.conversation()));
        assertTrue(session.conversation().path(0).path("original_user_context").asText()
                .contains("ORIGINAL_TASK: preserve the initial objective."));

        SessionUsage usage = session.usage();
        assertEquals(116, usage.inputTokens(), "summary usage is included once with all four ordinary requests");
        assertEquals(18, usage.outputTokens());
        SessionStore.Snapshot persisted = new SessionStore(json, state).load(session.id());
        assertEquals(usage.inputTokens(), persisted.usage().inputTokens());
        assertEquals(usage.outputTokens(), persisted.usage().outputTokens());
        SessionRuntime resumed = SessionRuntime.start(agent(new ScriptedClient(), instructions, tool, budget),
                new SessionStore(json, state), workspace, "model", instructions, session.id());
        assertEquals(persisted.input(), resumed.conversation(), "the compacted history resumes intact");
        assertEquals(usage.inputTokens(), resumed.usage().inputTokens());
        assertEquals(usage.outputTokens(), resumed.usage().outputTokens());
    }

    @Test
    void irreduciblePinnedRequestFailsBeforeAnySummaryOrGatewayCallAndStaysDurable() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        String instructions = "system";
        ContextBudget budget = new ContextBudget(500, 80, 0);
        ScriptedClient client = new ScriptedClient();
        SessionRuntime session = SessionRuntime.start(agent(client, instructions, null, budget),
                new SessionStore(json, state), workspace, "model", instructions, null);

        String oversized = "ORIGINAL_TASK: " + "constraint ".repeat(900);
        IOException first = assertThrows(IOException.class, () -> session.prompt(oversized));
        assertTrue(first.getMessage().contains("cannot safely compact"), first.getMessage());
        assertEquals(0, client.requests.size(), "preflight fails before summary and normal requests");
        ArrayNode firstPersisted = new SessionStore(json, state).load(session.id()).input();
        assertEquals(session.conversation(), firstPersisted);
        assertEquals(0, countSummaries(session.conversation()));

        assertThrows(IOException.class, () -> session.prompt("another request"));
        assertEquals(0, client.requests.size(), "retry does not repeatedly pay for an impossible summary");
        assertEquals(session.conversation(), new SessionStore(json, state).load(session.id()).input());
        assertEquals(0, session.usage().inputTokens());
        assertEquals(0, session.usage().outputTokens());
    }

    @Test
    void failedCompactedHistoryCheckpointDoesNotSwapLiveHistoryAndCountsSummaryUsageOnce() throws Exception {
        String instructions = "system";
        LoopTool tool = new LoopTool();
        ContextBudget budget = new ContextBudget(800, 100, 0);
        ScriptedClient client = new ScriptedClient();
        Agent agent = agent(client, instructions, tool, budget);
        ArrayNode seed = seededHistory("ORIGINAL_TASK: preserve this requirement.", "prior answer",
                "CURRENT_TASK: keep this latest request.", 1, OLD_RESULT);
        agent.restoreConversation(seed, instructions);
        ArrayNode expectedOriginal = seed.deepCopy();
        expectedOriginal.addObject().put("role", "user").put("content", "FOLLOWUP_TASK: retain this too.");

        long[] persistedUsage = {0, 0};
        List<long[]> usageEvents = new ArrayList<>();
        Agent.HistoryCheckpoint checkpoint = (history, input, output) -> {
            if (countSummaries(history) > 0) throw new IOException("injected compacted-history save failure");
            persistedUsage[0] += Math.max(0, input - persistedUsage[0]);
            persistedUsage[1] += Math.max(0, output - persistedUsage[1]);
        };
        IOException failed = assertThrows(IOException.class, () -> agent.prompt(
                "FOLLOWUP_TASK: retain this too.", ignored -> { }, new Agent.TurnListener() {
                    @Override public void onToolStart(String name, String preview) { }
                    @Override public void onToolEnd(String name, boolean error) { }
                    @Override public void onUsage(long inputTokens, long outputTokens) {
                        usageEvents.add(new long[]{inputTokens, outputTokens});
                    }
                }, checkpoint));

        assertTrue(failed.getMessage().contains("injected compacted-history save failure"), failed.getMessage());
        assertEquals(expectedOriginal, agent.snapshotInput(), "failed save leaves live history un-compacted");
        assertEquals(1, client.summaryRequests);
        assertEquals(1, usageEvents.size());
        assertEquals(70, usageEvents.get(0)[0]);
        assertEquals(8, usageEvents.get(0)[1]);
        assertEquals(70, persistedUsage[0], "summary input usage is durably added only once");
        assertEquals(8, persistedUsage[1]);
    }

    private Agent agent(ScriptedClient client, String instructions, LoopTool tool, ContextBudget budget) {
        List<Tool> tools = tool == null ? List.of() : List.of(tool);
        return new Agent(json, client, tools, (name, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 10, instructions, null, null, budget);
    }

    private long fixedTokens(ContextBudget budget, String instructions, LoopTool tool) {
        ArrayNode definitions = json.createArrayNode();
        if (tool != null) definitions.add(tool.definition(json));
        return budget.estimateTextTokens(instructions) + budget.estimateTokens(definitions);
    }

    private ArrayNode seededHistory(String original, String priorAnswer, String latest,
                                    int round, String toolOutput) {
        ArrayNode history = json.createArrayNode();
        history.addObject().put("role", "user").put("content", original);
        addAssistantText(history, priorAnswer);
        history.addObject().put("role", "user").put("content", latest);
        appendToolRound(history, round, toolOutput);
        return history;
    }

    private void appendToolRound(ArrayNode history, int round, String toolOutput) {
        history.addObject().put("type", "reasoning").put("encrypted_content", "opaque-" + round)
                .putArray("summary");
        ObjectNode note = history.addObject();
        note.put("id", "note-" + round).put("type", "message").put("role", "assistant")
                .put("status", "completed");
        note.putArray("content").addObject().put("type", "output_text")
                .put("text", "Preparing tool round " + round).putArray("annotations");
        history.addObject().put("id", "call-item-" + round).put("type", "function_call")
                .put("call_id", "call-" + round).put("name", "round_tool")
                .put("arguments", "{\"round\":" + round + "}").put("status", "completed");
        history.addObject().put("type", "function_call_output").put("call_id", "call-" + round)
                .put("output", toolOutput);
    }

    private void addAssistantText(ArrayNode history, String value) {
        ObjectNode message = history.addObject();
        message.put("id", "old-answer").put("type", "message").put("role", "assistant")
                .put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text").put("text", value)
                .putArray("annotations");
    }

    private ObjectNode textResponse(String text, long inputTokens, long outputTokens) {
        ObjectNode response = response(inputTokens, outputTokens);
        addAssistantText(response.putArray("output"), text);
        return response;
    }

    private ObjectNode toolResponse(int round, long inputTokens, long outputTokens) {
        ObjectNode response = response(inputTokens, outputTokens);
        ArrayNode output = response.putArray("output");
        output.addObject().put("id", "reason-" + round).put("type", "reasoning")
                .put("encrypted_content", "opaque-" + round).putArray("summary");
        ObjectNode note = output.addObject();
        note.put("id", "note-" + round).put("type", "message").put("role", "assistant")
                .put("status", "completed");
        note.putArray("content").addObject().put("type", "output_text")
                .put("text", "Preparing tool round " + round).putArray("annotations");
        output.addObject().put("id", "call-item-" + round).put("type", "function_call")
                .put("call_id", "call-" + round).put("name", "round_tool")
                .put("arguments", "{\"round\":" + round + "}").put("status", "completed");
        return response;
    }

    private ObjectNode response(long inputTokens, long outputTokens) {
        ObjectNode response = json.createObjectNode().put("id", "response").put("status", "completed");
        response.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", outputTokens);
        return response;
    }

    private static boolean hasPair(ArrayNode history, String callId) {
        boolean call = false;
        boolean output = false;
        for (JsonNode item : history) {
            if (!item.path("call_id").asText().equals(callId)) continue;
            if (item.path("type").asText().equals("function_call")) call = true;
            if (item.path("type").asText().equals("function_call_output")) output = true;
        }
        return call && output;
    }

    private static boolean hasAnyToolRecord(ArrayNode history, String callId) {
        for (JsonNode item : history) {
            if (item.path("call_id").asText().equals(callId)
                    && (item.path("type").asText().equals("function_call")
                    || item.path("type").asText().equals("function_call_output"))) return true;
        }
        return false;
    }

    private static int countSummaries(ArrayNode history) {
        int count = 0;
        for (JsonNode item : history) {
            if (item.path("type").asText().equals("compacted_summary")) count++;
        }
        return count;
    }

    private final class LoopTool implements Tool {
        @Override public String name() { return "round_tool"; }
        @Override public String description() { return "Return a deterministic round result"; }
        @Override public ObjectNode parameters() {
            ObjectNode schema = json().createObjectNode().put("type", "object");
            schema.putObject("properties").putObject("round").put("type", "integer");
            schema.putArray("required").add("round");
            return schema;
        }
        @Override public boolean requiresApproval() { return false; }
        @Override public String preview(JsonNode arguments) { return "round " + arguments.path("round").asInt(); }
        @Override public String execute(JsonNode arguments) {
            return arguments.path("round").asInt() == 1 ? OLD_RESULT : RECENT_RESULT;
        }
        private ObjectMapper json() { return AutomaticContextManagementTest.this.json; }
    }

    private final class ScriptedClient implements ResponsesClient {
        private final List<Request> requests = new ArrayList<>();
        private int normalIndex;
        private int summaryRequests;

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) throws IOException {
            requests.add(new Request(input.deepCopy(), tools.deepCopy(), instructions));
            if (instructions.equals(ConversationCompactor.SUMMARIZER_INSTRUCTIONS)) {
                summaryRequests++;
                return textResponse("Summary keeps the original objective and latest tool result.", 70, 8);
            }
            switch (normalIndex++) {
                case 0: return textResponse("old answer", 5, 1);
                case 1: return toolResponse(1, 11, 2);
                case 2: return toolResponse(2, 13, 3);
                case 3: return textResponse("Done", 17, 4);
                default: throw new IOException("unexpected model request");
            }
        }
    }

    private static final class Request {
        private final ArrayNode input;
        private final ArrayNode tools;
        private final String instructions;

        private Request(ArrayNode input, ArrayNode tools, String instructions) {
            this.input = input;
            this.tools = tools;
            this.instructions = instructions;
        }
    }
}
