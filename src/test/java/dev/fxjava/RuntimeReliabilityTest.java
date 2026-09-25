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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RuntimeReliabilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void cancellationCheckpointsCompletedAndUnstartedCallsAndDoesNotReplay() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        AtomicInteger actions = new AtomicInteger();
        CountingTool tool = new CountingTool(actions, true);
        ScriptedClient firstClient = new ScriptedClient(functionResponse(17, 3,
                call("first", "mutate", "{}"), call("second", "mutate", "{}")));
        SessionRuntime session = SessionRuntime.start(agent(firstClient, tool),
                new SessionStore(json, state), workspace, "model", "instructions", null);
        List<String> events = new ArrayList<>();

        assertThrows(InterruptedException.class, () -> session.prompt("perform two actions", ignored -> { },
                new Agent.TurnListener() {
                    @Override public void onToolStart(String name, String preview) {
                        events.add("start:" + name);
                    }
                    @Override public void onToolEnd(String name, boolean error) {
                        events.add("end:" + name + ":" + error);
                    }
                    @Override public void onUsage(long inputTokens, long outputTokens) { }
                }));
        Thread.interrupted(); // Agent deliberately restores cancellation on the calling worker.

        assertEquals(1, actions.get());
        assertEquals(List.of("start:mutate", "end:mutate:false"), events,
                "the started action receives one matching completion event; skipped calls stay unstarted");
        assertEquals(List.of(new Agent.ToolCallRecord("mutate", "success"),
                new Agent.ToolCallRecord("mutate", "error")), session.lastToolCalls());
        assertEquals(17, session.usage().inputTokens());
        assertEquals(3, session.usage().outputTokens());
        ArrayNode savedAfterCancellation = new SessionStore(json, state).load(session.id()).input();
        assertOutput(savedAfterCancellation, "first", "mutation-1");
        assertTrue(output(savedAfterCancellation, "second").asText().contains("not started"));

        ScriptedClient resumedClient = new ScriptedClient(textResponse("continued", 5, 1));
        SessionRuntime resumed = SessionRuntime.start(agent(resumedClient,
                new CountingTool(actions, false)), new SessionStore(json, state), workspace,
                "model", "instructions", session.id());
        assertEquals("continued", resumed.prompt("continue"));
        assertEquals(1, actions.get(), "a restored function call is never replayed");
        assertEquals(22, resumed.usage().inputTokens(), "turn usage is persisted once across resume");
        assertEquals(4, resumed.usage().outputTokens());
        ArrayNode resumedRequest = resumedClient.requests.get(0);
        assertOutput(resumedRequest, "first", "mutation-1");
        assertTrue(output(resumedRequest, "second").asText().contains("not started"));
    }

    @Test
    void failedIntentCheckpointPreventsEveryToolSideEffect() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        Agent agent = agent(new ScriptedClient(functionResponse(0, 0,
                call("first", "mutate", "{}"), call("second", "mutate", "{}"))),
                new CountingTool(actions, false));
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<ArrayNode> durable = new AtomicReference<>();

        IOException failed = assertThrows(IOException.class, () -> agent.prompt("mutate", ignored -> { },
                Agent.TurnListener.NONE, (history, input, output) -> {
                    if (saves.incrementAndGet() == 2) throw new IOException("intent save failed");
                    durable.set(history.deepCopy());
                }));

        assertEquals("intent save failed", failed.getMessage());
        assertEquals(0, actions.get(), "the tool cannot run until its intent is durable");
        assertNotNull(durable.get());
        assertTrue(output(durable.get(), "first").asText().contains("not started"));
        assertTrue(output(durable.get(), "second").asText().contains("not started"));
    }

    @Test
    void failedResultCheckpointStopsBatchAndCrashRecoveryMarksUnknownCallsWithoutReplay() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        CountingTool tool = new CountingTool(actions, false);
        Agent agent = agent(new ScriptedClient(functionResponse(9, 2,
                call("first", "mutate", "{}"), call("second", "mutate", "{}"))), tool);
        AtomicInteger saves = new AtomicInteger();
        AtomicReference<ArrayNode> durable = new AtomicReference<>();

        IOException failed = assertThrows(IOException.class, () -> agent.prompt("mutate", ignored -> { },
                Agent.TurnListener.NONE, (history, input, output) -> {
                    int attempt = saves.incrementAndGet();
                    if (attempt <= 2) durable.set(history.deepCopy()); // user, then durable tool intent
                    else throw new IOException("result save unavailable");
                }));
        assertEquals("result save unavailable", failed.getMessage());
        assertEquals(1, actions.get(), "the second tool must stop after the first result cannot be saved");
        assertNotNull(durable.get());
        assertTrue(output(durable.get(), "first") == null, "disk still has only the pre-action intent");

        ScriptedClient resumedClient = new ScriptedClient(textResponse("recovered", 0, 0));
        Agent resumed = agent(resumedClient, new CountingTool(actions, false));
        resumed.restoreConversation(durable.get(), "instructions");
        assertEquals("recovered", resumed.prompt("continue"));
        assertEquals(1, actions.get(), "uncertain mutations are recorded and never retried");
        assertTrue(output(resumedClient.requests.get(0), "first").asText().contains("uncertain"));
        assertTrue(output(resumedClient.requests.get(0), "second").asText().contains("uncertain"));
    }

    @Test
    void commandOutcomesUseExitAndTimeoutStatusAndCleanupKnownChildren() throws Exception {
        assumeFalse(isWindows(), "these process assertions use the Linux shell");
        Tool command = named(WorkspaceTools.create(temporary), "run_command");

        ToolResult nonzero = command.executeResult(json.createObjectNode()
                .put("command", "exit 7"), "nonzero");
        assertEquals(ToolResult.Status.ERROR, nonzero.status());
        assertTrue(nonzero.output().contains("Exit code: 7"));

        ToolResult timeout = command.executeResult(json.createObjectNode()
                .put("command", "sleep 30 & echo $!; wait")
                .put("timeout_seconds", 1), "timeout");
        assertEquals(ToolResult.Status.TIMEOUT, timeout.status());
        assertTrue(timeout.output().contains("timed out"));
        long childPid = timeout.output().lines().map(String::trim).filter(value -> value.matches("[0-9]+"))
                .mapToLong(Long::parseLong).findFirst().orElseThrow();
        awaitDead(childPid, Duration.ofSeconds(2));
    }

    @Test
    void fastShellExitWithInheritedPipeStillReturnsWithinCollectorBound() throws Exception {
        assumeFalse(isWindows(), "this process assertion uses the Linux shell");
        Tool command = named(WorkspaceTools.create(temporary), "run_command");
        long started = System.nanoTime();
        ToolResult result = command.executeResult(json.createObjectNode()
                .put("command", "sleep 10 & echo $!; exit 7")
                .put("timeout_seconds", 5), "retained-pipe");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(result.isError());
        assertTrue(elapsedMillis < 4_000, "an orphan holding the inherited pipe cannot hang the worker");
        result.output().lines().map(String::trim).filter(value -> value.matches("[0-9]+"))
                .mapToLong(Long::parseLong).findFirst().ifPresent(RuntimeReliabilityTest::killIfAlive);
    }

    private Agent agent(ResponsesClient client, Tool tool) {
        return new Agent(json, client, List.of(tool), (ignoredTool, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 5, "instructions");
    }

    private ObjectNode functionResponse(long inputTokens, long outputTokens, ObjectNode... calls) {
        ObjectNode response = json.createObjectNode().put("status", "completed");
        response.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", outputTokens);
        ArrayNode output = response.putArray("output");
        for (ObjectNode call : calls) output.add(call);
        return response;
    }

    private ObjectNode call(String id, String name, String arguments) {
        return json.createObjectNode().put("type", "function_call").put("call_id", id)
                .put("name", name).put("arguments", arguments);
    }

    private ObjectNode textResponse(String text, long inputTokens, long outputTokens) {
        ObjectNode response = json.createObjectNode().put("status", "completed");
        response.putObject("usage").put("input_tokens", inputTokens).put("output_tokens", outputTokens);
        response.putArray("output").addObject().put("type", "message")
                .putArray("content").addObject().put("type", "output_text").put("text", text);
        return response;
    }

    private static JsonNode output(ArrayNode history, String callId) {
        for (JsonNode item : history) {
            if (item.path("type").asText().equals("function_call_output")
                    && item.path("call_id").asText().equals(callId)) return item.path("output");
        }
        return null;
    }

    private static void assertOutput(ArrayNode history, String callId, String expected) {
        JsonNode actual = output(history, callId);
        assertNotNull(actual, "missing function result for " + callId);
        assertTrue(actual.asText().contains(expected), actual.asText());
    }

    private static void awaitDead(long pid, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return;
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "captured descendant should be gone after timeout cleanup");
    }

    private static void killIfAlive(long pid) {
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static Tool named(List<Tool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static final class CountingTool implements Tool {
        private final AtomicInteger executions;
        private final boolean interruptAfterAction;

        CountingTool(AtomicInteger executions, boolean interruptAfterAction) {
            this.executions = executions;
            this.interruptAfterAction = interruptAfterAction;
        }

        @Override public String name() { return "mutate"; }
        @Override public String description() { return "test mutation"; }
        @Override public ObjectNode parameters() { return new ObjectMapper().createObjectNode().put("type", "object"); }
        @Override public boolean requiresApproval() { return false; }
        @Override public String preview(JsonNode arguments) { return "mutate"; }
        @Override public String execute(JsonNode arguments) {
            int result = executions.incrementAndGet();
            if (interruptAfterAction) Thread.currentThread().interrupt();
            return "mutation-" + result;
        }
    }

    private static final class ScriptedClient implements ResponsesClient {
        private final List<ObjectNode> responses;
        private final List<ArrayNode> requests = new ArrayList<>();
        private int index;

        ScriptedClient(ObjectNode... responses) { this.responses = List.of(responses); }

        @Override public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
                throws IOException {
            requests.add(input.deepCopy());
            if (index >= responses.size()) throw new IOException("unexpected provider request");
            return responses.get(index++).deepCopy();
        }
    }
}
