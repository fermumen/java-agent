package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Usage parsing, per-turn listener delivery, and durable per-session totals. */
class UsageTrackingTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void parseUsageReadsPresentFieldsAndToleratesAbsenceAndMalformedShapes() throws Exception {
        assertArray(1234, 567, "{\"usage\":{\"input_tokens\":1234,\"output_tokens\":567}}");
        assertArray(0, 0, "{}");
        assertArray(0, 0, "{\"usage\":\"oops\"}");
        assertArray(0, 10, "{\"usage\":{\"input_tokens\":-5,\"output_tokens\":10}}");
        assertArray(0, 7, "{\"usage\":{\"output_tokens\":7,\"input_tokens\":\"many\"}}");
        // fx requires integral counts: fractional fields count as zero, not truncated.
        assertArray(0, 9, "{\"status\":\"completed\",\"usage\":{\"input_tokens\":4.0,"
                + "\"output_tokens\":9},\"output\":[]}");
        assertArray(3, 0, "{\"usage\":{\"input_tokens\":3,\"output_tokens\":4.5}}");
    }

    private void assertArray(long input, long output, String response) throws IOException {
        long[] usage = OpenAiResponsesClient.parseUsage(json.readTree(response));
        assertEquals(input, usage[0]);
        assertEquals(output, usage[1]);
    }

    @Test
    void turnListenerReceivesTotalsAccumulatedAcrossToolSteps() throws Exception {
        ScriptedClient client = new ScriptedClient(
                toolResponse("call-1", "echo", "{\"value\":\"hello\"}", 100, 10),
                textResponse("Done", 30, 3));
        AtomicReference<long[]> reported = new AtomicReference<>();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Done", agent.prompt("Do it", ignored -> { }, new Agent.TurnListener() {
            @Override public void onToolStart(String name, String preview) { }
            @Override public void onToolEnd(String name, boolean error) { }
            @Override public void onUsage(long inputTokens, long outputTokens) {
                reported.set(new long[]{inputTokens, outputTokens});
            }
        }));

        assertNotNull(reported.get());
        assertEquals(130, reported.get()[0], "input totals accumulate across steps");
        assertEquals(13, reported.get()[1], "output totals accumulate across steps");
        assertEquals(2, client.requests.size());
    }

    @Test
    void absentUsageStillDeliversZeroTotalsExactlyOncePerTurn() throws Exception {
        ScriptedClient client = new ScriptedClient(textResponse("Plain", 0, 0));
        List<long[]> deliveries = new ArrayList<>();
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Plain", agent.prompt("hi", ignored -> { }, new Agent.TurnListener() {
            @Override public void onToolStart(String name, String preview) { }
            @Override public void onToolEnd(String name, boolean error) { }
            @Override public void onUsage(long inputTokens, long outputTokens) {
                deliveries.add(new long[]{inputTokens, outputTokens});
            }
        }));

        assertEquals(1, deliveries.size());
        assertEquals(0, deliveries.get(0)[0]);
        assertEquals(0, deliveries.get(0)[1]);
    }

    @Test
    void promptWithoutListenerKeepsWorkingWithUsagePayloads() throws Exception {
        ScriptedClient client = new ScriptedClient(textResponse("Quiet", 11, 2));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> true,
                new PrintStream(progress, true, StandardCharsets.UTF_8), 5, "system");

        assertEquals("Quiet", agent.prompt("hello"));
        assertTrue(progress.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void sessionUsageRoundTripsThroughPersistResumeAndRecover() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionRuntime session = runtime(workspace, state,
                textResponse("one", 100, 10), textResponse("two", 200, 20));
        session.prompt("first");
        session.prompt("second");
        assertEquals(300, session.usage().inputTokens());
        assertEquals(30, session.usage().outputTokens());

        SessionStore.Snapshot loaded = new SessionStore(json, state).load(session.id());
        assertEquals(300, loaded.usage().inputTokens());
        assertEquals(30, loaded.usage().outputTokens());

        SessionRuntime resumed = SessionRuntime.start(agent(client(textResponse("three", 50, 5))),
                new SessionStore(json, state), workspace, "model", "instructions", session.id());
        assertEquals(300, resumed.usage().inputTokens(), "resume restores persisted totals");
        resumed.prompt("third");
        assertEquals(350, resumed.usage().inputTokens());
        assertEquals(35, resumed.usage().outputTokens());

        SessionStore.Snapshot recovered = new SessionStore(json, state)
                .load(new SessionStore(json, state).latest(workspace).id());
        assertEquals(350, recovered.usage().inputTokens());

        SessionRuntime recoveryTarget = SessionRuntime.start(agent(client(textResponse("x", 0, 0))),
                new SessionStore(json, state), workspace, "model", "instructions", session.id());
        recoveryTarget.recover(session.id(), workspace);
        assertEquals(350, recoveryTarget.usage().inputTokens(),
                "recover copies the source usage into the recovered session");
    }

    @Test
    void ruleAndUsageMutationsInOneProcessDoNotLoseUpdates() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionRuntime session = runtime(workspace, state,
                textResponse("answer", 400, 40),
                textResponse("answer-2", 60, 6));
        String key = SessionRules.normalizeArguments(json.readTree("{\"path\":\"a.md\"}"));
        assertNotNull(session.rememberRule(SessionRules.Kind.ALLOW, "write_file", key));
        session.prompt("turn with usage");
        session.prompt("second turn with usage");
        assertNotNull(session.rememberRule(SessionRules.Kind.DENY, "terminal", "{}"));

        SessionStore.Snapshot merged = new SessionStore(json, state).load(session.id());
        assertEquals(2, merged.rules().count());
        assertEquals(460, merged.usage().inputTokens());
        assertEquals(46, merged.usage().outputTokens());
        assertEquals(SessionRules.Decision.ALLOW,
                merged.rules().decide("write_file", key));
    }

    @Test
    void noSaveModeTracksInMemoryAndPersistsNothing() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        SessionRuntime unsaved = SessionRuntime.start(agent(client(textResponse("a", 12, 3))),
                null, workspace, "model", "instructions", null);
        unsaved.prompt("prompt");

        assertFalse(unsaved.persistent());
        assertEquals(12, unsaved.usage().inputTokens());
        assertEquals(3, unsaved.usage().outputTokens());
        assertTrue(unsaved.allTimeSessions(10).sessions().isEmpty());
    }

    @Test
    void explicitNullUsageStateFailsClosedLikeOtherInvalidState() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        SessionStore.Snapshot created = store.create(workspace, "model", "system");
        Path file = state.resolve("sessions").resolve(created.id()).resolve("session.json");

        ObjectNode nulled = (ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        nulled.putNull("usage_state");
        Files.writeString(file, json.writeValueAsString(nulled), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.load(created.id()),
                "explicit JSON null usage_state is invalid, not absent");
    }

    @Test
    void preUsageV3SnapshotsLoadAsZeroedStateAndGainExplicitUsageOnSave() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        SessionStore.Snapshot created = store.create(workspace, "model", "system");
        Path file = state.resolve("sessions").resolve(created.id()).resolve("session.json");
        ObjectNode legacy = (ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        legacy.remove("usage_state");
        Files.writeString(file, json.writeValueAsString(legacy), StandardCharsets.UTF_8);

        SessionStore.Snapshot migrated = store.load(created.id());
        assertEquals(0, migrated.usage().inputTokens());
        assertEquals(0, migrated.usage().outputTokens());

        store.update(migrated, migrated.input(), migrated.instructions(), 42, 7);
        ObjectNode saved = (ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(42, saved.path("usage_state").path("input_tokens").asLong());
        assertEquals(7, saved.path("usage_state").path("output_tokens").asLong());

        ObjectNode corrupt = ((ObjectNode) json.readTree(Files.readString(file, StandardCharsets.UTF_8)))
                .putObject("usage_state").put("schema_version", 1);
        corrupt.putNull("input_tokens");
        Files.writeString(file, json.writeValueAsString(corrupt), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> store.load(created.id()),
                "present but invalid usage state fails closed");
    }

    @Test
    void failureAfterPartialStepsFoldsConsumedTokensIntoPersistence() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        ScriptedClient client = new ScriptedClient(
                toolResponse("call-1", "echo", "{\"value\":\"hello\"}", 100, 10));
        client.failAfterScript = true;
        SessionRuntime session = SessionRuntime.start(agent(client), new SessionStore(json, state),
                workspace, "model", "instructions", null);

        IOException failed = assertThrows(IOException.class, () -> session.prompt("explode"));
        assertEquals("provider exploded mid-turn", failed.getMessage());
        assertEquals(100, session.usage().inputTokens(), "partial input tokens survive the failure");
        assertEquals(10, session.usage().outputTokens(), "partial output tokens survive the failure");

        SessionStore.Snapshot persisted = new SessionStore(json, state).load(session.id());
        assertEquals(100, persisted.usage().inputTokens(), "failed turns still persist consumed tokens");
        assertEquals(10, persisted.usage().outputTokens());
    }

    @Test
    void stepLimitExhaustionFoldsEveryConsumedStep() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        Agent agent = new Agent(json,
                new ScriptedClient(
                        toolResponse("call-1", "echo", "{\"value\":\"a\"}", 100, 10),
                        toolResponse("call-2", "echo", "{\"value\":\"b\"}", 50, 5)),
                List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(PrintStream.nullOutputStream()), 2, "instructions");
        SessionRuntime session = SessionRuntime.start(agent, store, workspace, "model",
                "instructions", null);
        List<long[]> deliveries = new ArrayList<>();

        IOException exhausted = assertThrows(IOException.class, () ->
                session.prompt("loop forever", ignored -> { }, new Agent.TurnListener() {
                    @Override public void onToolStart(String name, String preview) { }
                    @Override public void onToolEnd(String name, boolean error) { }
                    @Override public void onUsage(long inputTokens, long outputTokens) {
                        deliveries.add(new long[]{inputTokens, outputTokens});
                    }
                }));
        assertTrue(exhausted.getMessage().contains("step limit"));

        assertEquals(1, deliveries.size(), "failure finalizes the accumulator exactly once");
        assertEquals(150, deliveries.get(0)[0], "all consumed steps are folded in");
        assertEquals(15, deliveries.get(0)[1]);
        assertEquals(150, session.usage().inputTokens());
        assertEquals(15, session.usage().outputTokens());
        assertEquals(150, store.load(session.id()).usage().inputTokens(),
                "max-steps exhaustion persists every consumed token");
    }

    private SessionRuntime runtime(Path workspace, Path state, ObjectNode... responses)
            throws IOException {
        return SessionRuntime.start(agent(client(responses)), new SessionStore(json, state),
                workspace, "model", "instructions", null);
    }

    private Agent agent(ResponsesClient client) {
        return new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(PrintStream.nullOutputStream()), 5, "instructions");
    }

    private ResponsesClient client(ObjectNode... responses) {
        return new ScriptedClient(responses);
    }

    private ObjectNode textResponse(String text, long inputTokens, long outputTokens) {
        ObjectNode response = completedResponse();
        response.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", outputTokens);
        ObjectNode message = response.putArray("output").addObject();
        message.put("id", "msg_1").put("type", "message").put("role", "assistant")
                .put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text").put("text", text)
                .putArray("annotations");
        return response;
    }

    private ObjectNode toolResponse(String callId, String name, String arguments,
                                    long inputTokens, long outputTokens) {
        ObjectNode response = completedResponse();
        response.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", outputTokens);
        ArrayNode output = response.putArray("output");
        output.addObject().put("id", "rs_1").put("type", "reasoning")
                .put("encrypted_content", "encrypted-state").putArray("summary");
        output.addObject().put("id", "fc_1").put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments)
                .put("status", "completed");
        return response;
    }

    private ObjectNode completedResponse() {
        return json.createObjectNode().put("id", "resp_test").put("object", "response")
                .put("status", "completed");
    }

    private final class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo a value"; }
        @Override public ObjectNode parameters() { return json.createObjectNode().put("type", "object"); }
        @Override public boolean requiresApproval() { return false; }
        @Override public String preview(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo " + arguments.path("value").asText();
        }
        @Override public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo: " + arguments.path("value").asText();
        }
    }

    private static final class ScriptedClient implements ResponsesClient {
        private final List<ObjectNode> responses;
        final List<ArrayNode> requests = new ArrayList<>();
        int index;
        boolean failAfterScript;

        ScriptedClient(ObjectNode... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
                throws IOException {
            requests.add(input.deepCopy());
            if (index < responses.size()) {
                ObjectNode next = responses.get(index++);
                return next.deepCopy();
            }
            if (failAfterScript) throw new IOException("provider exploded mid-turn");
            return responses.get(responses.size() - 1).deepCopy();
        }
    }
}
