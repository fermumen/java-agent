package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end /compact and usage flows against a local Responses double:
 * streaming generation payloads carry usage, summarization rides the
 * non-streaming seam, and durable snapshots stay coherent across restarts.
 */
class CompactionIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void compactionRoundTripPersistsImmediatelyAndConversationStaysCoherent() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            for (int index = 0; index < 5; index++) session.prompt("question " + index);
            ArrayNode before = session.conversation();
            assertEquals(10, before.size());

            CompactCommands.Result result = CompactCommands.run(session, "");

            assertEquals(CompactCommands.Outcome.COMPACTED, result.outcome);
            assertEquals(10, result.itemsBefore);
            assertEquals(1, result.compactionCount);
            assertEquals(result.itemsAfter, session.conversation().size());
            assertTrue(session.conversation().get(0).toString().contains("SUMMARY-1"));

            List<String> requests = api.requests();
            String summarizeRequest = requests.get(requests.size() - 1);
            assertTrue(summarizeRequest.contains("\"stream\":false"));
            assertTrue(summarizeRequest.contains("\"tools\":[]"),
                    "summarization carries no tool definitions");
            assertTrue(summarizeRequest.contains("[user] question 0"));
            assertTrue(summarizeRequest.contains("[assistant] answer-4"));

            SessionStore.Snapshot persisted = new SessionStore(json, state).load(session.id());
            assertEquals(result.itemsAfter, persisted.input().size(), "compaction persists immediately");
            assertEquals("compacted_summary", persisted.input().path(0).path("type").asText());
            assertEquals(1, persisted.input().path(0).path("compaction_count").asInt());

            String nextPromptRequest = null;
            session.prompt("after compaction");
            for (String request : api.requests()) {
                if (request.contains("after compaction")) nextPromptRequest = request;
            }
            assertTrue(nextPromptRequest.contains("SUMMARY-1"),
                    "the next turn replays the compacted summary");
            assertTrue(nextPromptRequest.contains("question 4"),
                    "recent exchanges stay verbatim in the replay");
        }
    }

    @Test
    void recompactionFoldsCountsInsideTheSummaryItem() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            for (int index = 0; index < 5; index++) session.prompt("question " + index);
            CompactCommands.run(session, "");
            for (int index = 0; index < 4; index++) session.prompt("more " + index);

            CompactCommands.Result second = CompactCommands.run(session, "");
            assertEquals(2, second.compactionCount, "re-compaction increments the folded count");

            SessionStore.Snapshot persisted = new SessionStore(json, state).load(session.id());
            ObjectNode summary = (ObjectNode) persisted.input().get(0);
            assertEquals("compacted_summary", summary.path("type").asText());
            assertEquals(2, summary.path("compaction_count").asInt());
            assertTrue(summary.path("summary_text").asText().contains("SUMMARY-2"));
        }
    }

    @Test
    void failedSummarizationLeavesConversationUntouchedWithFriendlyError() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(true)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            for (int index = 0; index < 4; index++) session.prompt("question " + index);
            ArrayNode before = session.conversation();
            SessionStore.Snapshot persistedBefore = new SessionStore(json, state).load(session.id());

            ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
            CompactCommands.handle(session, "", Ansi.of(false),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(errorBytes, true, StandardCharsets.UTF_8));

            assertTrue(errorBytes.toString(StandardCharsets.UTF_8)
                    .startsWith("java-agent: compaction failed:"));
            assertEquals(before, session.conversation(), "conversation is untouched on failure");
            assertEquals(persistedBefore,
                    new SessionStore(json, state).load(session.id()));
        }
    }

    @Test
    void refusalsAndMalformedArgumentsStayFriendly() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            session.prompt("only one turn");

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            CompactCommands.handle(session, "", Ansi.of(false), out,
                    new PrintStream(PrintStream.nullOutputStream()));
            assertTrue(bytes.toString(StandardCharsets.UTF_8)
                    .startsWith("Nothing to compact yet (2 items"));

            IllegalArgumentException shaped = assertThrows(IllegalArgumentException.class,
                    () -> CompactCommands.run(session, "extra"));
            assertEquals(CompactCommands.USAGE, shaped.getMessage());
        }
    }

    @Test
    void legacyShellRunsCompactEndToEndAndPrintsTokensLines() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("legacy-state");
        StringBuilder script = new StringBuilder();
        for (int index = 0; index < 5; index++) script.append("question ").append(index).append('\n');
        script.append("/compact\n/stats\n/exit\n");
        try (FakeApi api = new FakeApi(false)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
            int exit = Main.run(new String[]{"--base-url", api.baseUrl(),
                            "--workspace", workspace.toString(),
                            "--session-root", state.toString()},
                    Map.of("OPENAI_API_KEY", "test-key"),
                    new ByteArrayInputStream(script.toString().getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(bytes, true, StandardCharsets.UTF_8),
                    new PrintStream(errorBytes, true, StandardCharsets.UTF_8));

            assertEquals(0, exit);
            String output = bytes.toString(StandardCharsets.UTF_8) + "\n[stderr] "
                    + errorBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("tokens: 50 in · 5 out · 55 total"),
                    "legacy loop prints a plain per-turn tokens line: " + output);
            assertTrue(output.contains("Compacted: 10 → "), output);
            assertTrue(output.contains("compaction #1"), output);
            assertTrue(output.contains("session "), output);
            assertTrue(output.contains("all-time across 1 saved session"), output);
        }
    }

    @Test
    void noSaveModeCompactsInMemoryAndSaysSo() throws Exception {
        Path workspace = workspace();
        StringBuilder script = new StringBuilder();
        for (int index = 0; index < 5; index++) script.append("question ").append(index).append('\n');
        script.append("/compact\n/exit\n");
        try (FakeApi api = new FakeApi(false)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int exit = Main.run(new String[]{"--no-save", "--base-url", api.baseUrl(),
                            "--workspace", workspace.toString(),
                            "--session-root", temporary.resolve("nosave-state").toString()},
                    Map.of("OPENAI_API_KEY", "test-key"),
                    new ByteArrayInputStream(script.toString().getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(bytes, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

            assertEquals(0, exit);
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Compacted: 10 → "), output);
            assertTrue(output.contains("--no-save"), output);
            assertTrue(output.contains("in memory only") || output.contains("not persisted"),
                    output);
        }
    }

    @Test
    void rawShellDispatchesCompactThroughTheWorkerThreadSeam() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("raw-state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            for (int index = 0; index < 5; index++) session.prompt("question " + index);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
            AgentConfig config = new AgentConfig("key", api.baseUrl(), "model", workspace, 1,
                    PermissionMode.ASK);
            try (McpRuntime mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"))) {
                InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp,
                        state, new ByteArrayInputStream(new byte[0]), out, out, Ansi.of(false),
                        approval, "test", System::nanoTime);
                shell.dispatch("/compact extra");
                shell.dispatch("/compact");
                shell.dispatch("/stats");
            }
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Usage: /compact"));
            assertTrue(output.contains("Compacted: 10 → "), output);
            assertTrue(output.contains("compaction #1"), output);
            assertTrue(output.contains(": 320 in · 33 out · 353 total"),
                    "raw /stats shows cumulative session totals including /compact's own"
                            + " round-trip usage: " + output);
        }
    }

    @Test
    void compactAddsItsOwnRoundTripTokensToPersistedTotals() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            for (int index = 0; index < 5; index++) session.prompt("question " + index);
            long inputBefore = session.usage().inputTokens();
            long outputBefore = session.usage().outputTokens();

            CompactCommands.run(session, "");

            assertEquals(inputBefore + 70, session.usage().inputTokens(),
                    "/compact's own summarization tokens are metered");
            assertEquals(outputBefore + 8, session.usage().outputTokens());
            SessionStore.Snapshot persisted = new SessionStore(json, state).load(session.id());
            assertEquals(inputBefore + 70, persisted.usage().inputTokens(),
                    "compact usage reaches the durable totals");
            assertEquals(outputBefore + 8, persisted.usage().outputTokens());

            // Re-compaction folds its own round-trip again (the two prompts
            // above add their own 50/5 each).
            for (int index = 0; index < 2; index++) session.prompt("more " + index);
            CompactCommands.run(session, "");
            assertEquals(inputBefore + 240, session.usage().inputTokens());
        }
    }

    @Test
    void postCompactionWireCarriesProjectedSummaryInsteadOfRawItem() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            String sessionId = session.id();
            for (int index = 0; index < 5; index++) session.prompt("question " + index);

            CompactCommands.run(session, "");
            session.prompt("after compaction");

            ObjectNode wire = lastBodyContaining(api.generationBodies(), "after compaction");
            ArrayNode input = (ArrayNode) wire.path("input");
            assertEquals(0, countType(input, "compacted_summary"),
                    "the history-layer item must never reach the Responses API");

            JsonNode projected = input.get(0);
            assertEquals("user", projected.path("role").asText(),
                    "summary rides as a plain user message (instructions travel separately)");
            assertTrue(projected.path("content").asText()
                            .startsWith("[Summary of earlier conversation]\nSUMMARY-1"),
                    projected.path("content").asText());
            assertFalse(projected.has("type"));
            int keptStart = -1;
            for (int index = 1; index < input.size(); index++) {
                if (input.get(index).path("content").asText().equals("question 2")) keptStart = index;
            }
            assertTrue(keptStart > 0,
                    "projected summary precedes the kept exchanges, input was: " + input);
            assertTrue(projected.path("content").asText()
                            .contains("[Original user request retained verbatim]\nquestion 0"),
                    "the original user request remains available inside the summary projection");
            assertFalse(wire.toString().contains("\"content\":\"question 0\""),
                    "folded turns stay off the wire as separate history messages");
            assertToolPairsIntact(wire);

            SessionStore.Snapshot persisted = new SessionStore(json, state).load(sessionId);
            assertEquals("compacted_summary", persisted.input().get(0).path("type").asText(),
                    "durable snapshot keeps the raw item intact");
            assertEquals("SUMMARY-1", persisted.input().get(0).path("summary_text").asText());
            assertEquals(1, persisted.input().get(0).path("compaction_count").asInt());
            assertEquals("compacted_summary", session.conversation().get(0).path("type").asText(),
                    "projection happens on request copies only");
        }
    }

    @Test
    void resumeFromCompactedSnapshotKeepsWireCleanAndToolPairsIntact() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            String sessionId = session.id();
            for (int index = 0; index < 5; index++) session.prompt("question " + index);
            CompactCommands.run(session, "");

            api.armNextToolCall();
            session.prompt("probe after compaction");

            List<ObjectNode> bodies = api.generationBodies();
            assertToolPairsIntact(bodies.get(bodies.size() - 1));

            SessionRuntime resumed = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", sessionId);
            resumed.prompt("turn after restart");

            ObjectNode wire = lastBodyContaining(api.generationBodies(), "turn after restart");
            ArrayNode input = (ArrayNode) wire.path("input");
            assertEquals(0, countType(input, "compacted_summary"),
                    "resumed-from-snapshot requests stay API-safe too");
            assertTrue(input.get(0).path("content").asText()
                    .startsWith("[Summary of earlier conversation]\nSUMMARY-1"));
            assertEquals(1, countType(input, "function_call"));
            assertEquals(1, countType(input, "function_call_output"));
            assertToolPairsIntact(wire);
        }
    }

    /** Last captured generation body whose serialized input mentions {@code needle}. */
    private static ObjectNode lastBodyContaining(List<ObjectNode> bodies, String needle) {
        ObjectNode match = null;
        for (ObjectNode body : bodies) {
            if (body.toString().contains(needle)) match = body;
        }
        assertNotNull(match, "no captured request mentioned " + needle);
        return match;
    }

    private static int countType(ArrayNode input, String type) {
        int count = 0;
        for (JsonNode item : input) {
            if (item.path("type").asText().equals(type)) count++;
        }
        return count;
    }

    /**
     * Every function_call_output answers a function_call seen earlier in the
     * same body exactly once — repairInterruptedToolCalls injecting spurious
     * outputs after a compacted restore would trip the dangling-output branch.
     */
    private static void assertToolPairsIntact(ObjectNode body) {
        Set<String> open = new LinkedHashSet<>();
        Set<String> answered = new LinkedHashSet<>();
        for (JsonNode item : body.path("input")) {
            String type = item.path("type").asText();
            String callId = item.path("call_id").asText();
            if (type.equals("function_call")) {
                assertTrue(open.add(callId), "duplicate function_call " + callId);
            } else if (type.equals("function_call_output")) {
                assertTrue(open.remove(callId),
                        "output without a preceding call in: " + body.path("input"));
                assertTrue(answered.add(callId), "double-answered call " + callId);
            }
        }
        assertTrue(open.isEmpty(), "unanswered calls remain: " + open);
    }

    private Agent agent(String baseUrl) {
        return new Agent(json, new OpenAiResponsesClient(json, config(baseUrl)), List.of(),
                (tool, arguments) -> true, new PrintStream(PrintStream.nullOutputStream()), 5,
                "instructions");
    }

    private static ObjectNode streamedText(ObjectMapper json, int index) {
        ObjectNode response = json.createObjectNode().put("id", "resp_" + index)
                .put("object", "response").put("status", "completed");
        response.putObject("usage").put("input_tokens", 50).put("output_tokens", 5);
        ObjectNode message = response.putArray("output").addObject();
        message.put("id", "msg_" + index).put("type", "message").put("role", "assistant")
                .put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text")
                .put("text", "answer-" + index).putArray("annotations");
        return response;
    }

    private static ObjectNode toolCallThenFail(ObjectMapper json) {
        ObjectNode response = json.createObjectNode().put("id", "resp_tool")
                .put("object", "response").put("status", "completed");
        response.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
        ArrayNode output = response.putArray("output");
        output.addObject().put("id", "rs_1").put("type", "reasoning")
                .put("encrypted_content", "encrypted-state").putArray("summary");
        output.addObject().put("id", "fc_1").put("type", "function_call")
                .put("call_id", "call-1").put("name", "echo").put("arguments", "{\"value\":\"x\"}")
                .put("status", "completed");
        return response;
    }

    private final class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo a value"; }
        @Override public ObjectNode parameters() {
            return json.createObjectNode().put("type", "object");
        }
        @Override public boolean requiresApproval() { return false; }
        @Override public String preview(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo " + arguments.path("value").asText();
        }
        @Override public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo: " + arguments.path("value").asText();
        }
    }

    /**
     * Streaming turns come from a scripted queue; the summarization seam
     * deletes the session snapshot before answering, forcing the compaction
     * persist to fail after the model round-trip succeeded.
     */
    private static final class VanishingSummarizerClient implements ResponsesClient {
        private final ObjectMapper json;
        private final Path state;
        private final SessionStore store;
        final java.util.Queue<ObjectNode> streamed = new java.util.ArrayDeque<>();

        VanishingSummarizerClient(ObjectMapper json, Path state, SessionStore store) {
            this.json = json;
            this.state = state;
            this.store = store;
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
                throws IOException {
            // Summarization seam: destroy durable state mid-flight.
            java.util.List<SessionStore.Snapshot> all = store.list(null, 100);
            for (SessionStore.Snapshot snapshot : all) {
                Files.deleteIfExists(state.resolve("sessions").resolve(snapshot.id())
                        .resolve("session.json"));
            }
            ObjectNode response = json.createObjectNode().put("id", "resp_summary")
                    .put("object", "response").put("status", "completed");
            ObjectNode message = response.putArray("output").addObject();
            message.put("id", "msg_s").put("type", "message").put("role", "assistant")
                    .put("status", "completed");
            message.putArray("content").addObject().put("type", "output_text")
                    .put("text", "SUMMARY-VANISHED").putArray("annotations");
            return response;
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions,
                                   java.util.function.Consumer<String> textDelta) {
            return streamed.remove();
        }
    }

    /** Streaming turns work; the summarization seam throws something unexpected. */
    private static final class ThrowingSummarizerClient implements ResponsesClient {
        final java.util.Queue<ObjectNode> next = new java.util.ArrayDeque<>();

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            throw new IllegalStateException("summarizer exploded");
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions,
                                   java.util.function.Consumer<String> textDelta) {
            return next.remove();
        }
    }

    /** First streaming call answers with a tool call; the follow-up explodes. */
    private static final class FailingMidturnClient implements ResponsesClient {
        final java.util.Queue<ObjectNode> next = new java.util.ArrayDeque<>();
        int calls;

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
                throws IOException {
            throw new AssertionError("summarization seam is not used here");
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions,
                                   java.util.function.Consumer<String> textDelta) throws IOException {
            if (calls++ == 0) return next.remove();
            throw new IOException("provider exploded mid-turn");
        }
    }

    private AgentConfig config(String baseUrl) {
        return new AgentConfig("test-key", baseUrl, "model",
                Path.of("").toAbsolutePath(), 5, PermissionMode.YOLO);
    }

    private Path workspace() throws IOException {
        return Files.createDirectory(temporary.resolve("workspace-" + System.nanoTime()));
    }

    @Test
    void oversizedTranscriptsStillFeedThePriorSummaryToTheSummarizer() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        try (FakeApi api = new FakeApi(false)) {
            SessionRuntime session = SessionRuntime.start(agent(api.baseUrl()),
                    new SessionStore(json, state), workspace, "model", "instructions", null);
            for (int index = 0; index < 5; index++) session.prompt("question " + index);
            CompactCommands.run(session, "");
            // Two huge answers push the transcript past its byte budget.
            api.armLargeAnswer();
            session.prompt("grow one");
            api.armLargeAnswer();
            session.prompt("grow two");

            CompactCommands.Result second = CompactCommands.run(session, "");

            assertEquals(2, second.compactionCount,
                    "count only increments when the summary reached a real round-trip");
            String summarizeRequest = null;
            for (String request : api.requests()) {
                if (request.contains("\"stream\":false")) summarizeRequest = request;
            }
            assertNotNull(summarizeRequest);
            assertTrue(summarizeRequest.contains("Prior summary (from an earlier compaction):")
                            && summarizeRequest.contains("SUMMARY-1"),
                    "the folded prior summary survives transcript truncation: "
                            + summarizeRequest.substring(0, Math.min(400, summarizeRequest.length())));
            assertTrue(summarizeRequest.contains("(older transcript lines omitted)"),
                    "oversized transcripts still announce their trimming");
        }
    }

    @Test
    void persistFailureLeavesLiveConversationUntouchedWithNothingOnDisk() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        VanishingSummarizerClient client = new VanishingSummarizerClient(json, state, store);
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> true,
                new PrintStream(PrintStream.nullOutputStream()), 5, "instructions");
        SessionRuntime session = SessionRuntime.start(agent, store, workspace, "model",
                "instructions", null);
        String sessionId = session.id();
        for (int index = 0; index < 5; index++) client.streamed.add(streamedText(json, index));
        for (int index = 0; index < 5; index++) session.prompt("question " + index);
        ArrayNode before = session.conversation();

        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        CompactCommands.handle(session, "", Ansi.of(false),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(errorBytes, true, StandardCharsets.UTF_8));

        assertTrue(errorBytes.toString(StandardCharsets.UTF_8)
                        .contains("compaction failed: conversation left untouched"),
                errorBytes.toString(StandardCharsets.UTF_8));
        assertEquals(before, session.conversation(), "live conversation is never swapped on failure");
        assertFalse(Files.exists(state.resolve("sessions").resolve(sessionId)
                .resolve("session.json")), "no summary item or snapshot remains on disk");
    }

    @Test
    void throwingSummarizerSeamCannotKillTheShellAndStaysFriendly() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        ThrowingSummarizerClient client = new ThrowingSummarizerClient();
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> true,
                new PrintStream(PrintStream.nullOutputStream()), 5, "instructions");
        SessionRuntime session = SessionRuntime.start(agent, new SessionStore(json, state),
                workspace, "model", "instructions", null);
        for (int index = 0; index < 5; index++) client.next.add(streamedText(json, index));
        for (int index = 0; index < 5; index++) session.prompt("question " + index);
        ArrayNode before = session.conversation();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
        AgentConfig config = new AgentConfig("key", "http://127.0.0.1", "model", workspace, 1,
                PermissionMode.ASK);
        try (McpRuntime mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"))) {
            InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp,
                    state, new ByteArrayInputStream(new byte[0]), out, error, Ansi.of(false),
                    approval, "test", System::nanoTime);
            shell.dispatch("/compact");
            assertEquals(before, session.conversation(),
                    "a crashing summarizer leaves the conversation untouched");
            shell.dispatch("/stats");
        }
        assertTrue(errorBytes.toString(StandardCharsets.UTF_8)
                        .startsWith("java-agent: compaction failed: summarizer exploded"),
                errorBytes.toString(StandardCharsets.UTF_8));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(": 250 in · 25 out · 275 total"),
                "the shell survives and /stats still works afterwards");
    }

    @Test
    void failedTurnSuppressesTokensLineButFoldsPartialsIntoStats() throws Exception {
        Path workspace = workspace();
        Path state = temporary.resolve("state");
        FailingMidturnClient client = new FailingMidturnClient();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(PrintStream.nullOutputStream()), 5, "instructions");
        SessionRuntime session = SessionRuntime.start(agent, new SessionStore(json, state),
                workspace, "model", "instructions", null);
        client.next.add(toolCallThenFail(json));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
        AgentConfig config = new AgentConfig("key", "http://127.0.0.1", "model", workspace, 1,
                PermissionMode.ASK);
        try (McpRuntime mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"))) {
            InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp,
                    state, new ByteArrayInputStream(new byte[0]), out, error, Ansi.of(false),
                    approval, "test", System::nanoTime);
            shell.dispatch("make it fail");
            shell.dispatch("/stats");
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("tokens:"), "failed turns print no UI tokens line");
        assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("provider exploded"));
        assertTrue(output.contains(": 100 in · 10 out · 110 total"),
                "already-consumed step tokens reach /stats after the failure: " + output);
    }

    /** Streams generation responses with usage fields and answers summaries inline. */
    private static final class FakeApi implements AutoCloseable {
        private static final ObjectMapper WIRE = new ObjectMapper();
        private final HttpServer server;
        private final List<String> capturedRequests = new ArrayList<>();
        private final List<ObjectNode> capturedBodies = new ArrayList<>();
        private volatile boolean failingSummaries;
        private volatile int summaryCount;
        private volatile boolean toolCallArmed;
        private volatile boolean largeAnswerArmed;

        FakeApi(boolean failingSummaries) throws IOException {
            this.failingSummaries = failingSummaries;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        List<String> requests() {
            return List.copyOf(capturedRequests);
        }

        /** Parsed generation-request bodies in arrival order; summarization excluded. */
        List<ObjectNode> generationBodies() {
            synchronized (capturedBodies) {
                List<ObjectNode> copy = new ArrayList<>();
                for (ObjectNode body : capturedBodies) copy.add(body.deepCopy());
                return copy;
            }
        }

        void failingSummaries(boolean value) {
            this.failingSummaries = value;
        }

        /** Makes the next streaming turn answer with one function_call instead of text. */
        void armNextToolCall() {
            this.toolCallArmed = true;
        }

        /**
         * Makes the next streaming turn answer with a huge text payload, for
         * pushing the compaction transcript past its byte budget.
         */
        void armLargeAnswer() {
            this.largeAnswerArmed = true;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean streaming = request.contains("\"stream\":true");
            if (streaming) {
                ObjectNode body = (ObjectNode) WIRE.readTree(request);
                synchronized (capturedBodies) {
                    capturedBodies.add(body);
                }
            }
            synchronized (capturedRequests) {
                capturedRequests.add(request);
            }
            byte[] body;
            if (streaming && toolCallArmed) {
                toolCallArmed = false;
                String number = Integer.toString(capturedBodies.size());
                String response = "{\"id\":\"resp_tool_" + number + "\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2},"
                        + "\"output\":[{\"id\":\"fc_" + number + "\",\"type\":\"function_call\","
                        + "\"call_id\":\"call-post\",\"name\":\"post_compaction_probe\","
                        + "\"arguments\":\"{}\",\"status\":\"completed\"}]}";
                body = ("data: {\"type\":\"response.completed\",\"response\":" + response + "}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
            } else if (streaming) {
                String number = Integer.toString(capturedRequests.size());
                boolean large = largeAnswerArmed;
                largeAnswerArmed = false;
                String delta = large ? "XL-" + "A".repeat(60_000) : "answer-" + number;
                String response = "{\"id\":\"resp_" + number + "\",\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":50,\"output_tokens\":5},"
                        + "\"output\":[{\"id\":\"msg_" + number + "\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"status\":\"completed\",\"content\":["
                        + "{\"type\":\"output_text\",\"text\":\"" + delta + "\",\"annotations\":[]}]}]}";
                String events = large
                        ? "data: {\"type\":\"response.completed\",\"response\":" + response + "}\n\n"
                        : "data: {\"type\":\"response.output_text.delta\",\"delta\":\""
                                + delta + "\"}\n\n"
                                + "data: {\"type\":\"response.completed\",\"response\":"
                                + response + "}\n\n";
                body = events.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
            } else if (failingSummaries) {
                byte[] error = "{\"error\":{\"message\":\"summarizer unavailable\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                return;
            } else {
                int summaryNumber = ++summaryCount;
                String response = "{\"id\":\"resp_summary\",\"object\":\"response\","
                        + "\"status\":\"completed\","
                        + "\"usage\":{\"input_tokens\":70,\"output_tokens\":8},"
                        + "\"output\":[{\"id\":\"msg_s\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"status\":\"completed\",\"content\":["
                        + "{\"type\":\"output_text\",\"text\":\"SUMMARY-"
                        + summaryNumber + "\",\"annotations\":[]}]}]}";
                body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
