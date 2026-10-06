package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /stats aggregation across the active session, all-time totals, and recency breakdown. */
class StatsCommandsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void formatMatchesTheSharedTokensLine() {
        assertEquals("1234 in · 567 out · 1801 total", StatsCommands.format(1234, 567));
        assertEquals("0 in · 0 out · 0 total", StatsCommands.format(0, 0));
        assertEquals("↑ 999 ↓ 0", StatsCommands.compact(999, 0));
        assertEquals("↑ 1.2k ↓ 48k", StatsCommands.compact(1_234, 48_900));
        assertEquals("↑ 2k ↓ 3.4M", StatsCommands.compact(2_000, 3_456_789));
    }

    @Test
    void aggregatesCurrentAllTimeAndRecentSessionTotals() throws Exception {
        Path workspaceA = Files.createDirectory(temporary.resolve("workspace-a"));
        Path workspaceB = Files.createDirectory(temporary.resolve("workspace-b"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        SessionStore.Snapshot first = store.create(workspaceA, "model", "system");
        store.update(first, first.input(), first.instructions(), 100, 10);
        SessionStore.Snapshot second = store.create(workspaceB, "model", "system");
        store.update(second, second.input(), second.instructions(), 20, 2);
        SessionRuntime session = SessionRuntime.start(agent(), store, workspaceA,
                "model", "instructions", null);
        session.prompt("turn"); // ScriptedClient reports 50/5

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StatsCommands.handle(session, "", workspaceA, Ansi.of(false),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("session " + session.id() + ": 50 in · 5 out · 55 total"));
        assertTrue(output.contains("all-time across 3 saved sessions: 170 in · 17 out · 187 total"),
                output);
        assertTrue(output.contains(first.id()));
        assertTrue(output.contains("100 in · 10 out · 110 total"),
                "workspace-a breakdown lists this workspace's saved sessions: " + output);
        assertFalse(output.contains(second.id()), "breakdown stays workspace-filtered");
    }

    @Test
    void recentBreakdownIsCappedAtTenRowsAndMarksActiveSession() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        for (int index = 0; index < 13; index++) {
            SessionStore.Snapshot created = store.create(workspace, "model", "system");
            store.update(created, created.input(), created.instructions(), index + 1, 0);
        }
        SessionRuntime session = SessionRuntime.start(agent(), store, workspace,
                "model", "instructions", null);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StatsCommands.handle(session, "", workspace, Ansi.of(false),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("all-time across 14 saved sessions: 91 in · 0 out · 91 total"), output);
        assertEquals(10, occurrencesMatchingRow(output),
                "recent breakdown lists at most ten sessions");
    }

    private int occurrencesMatchingRow(String output) {
        int rows = 0;
        for (String line : output.split("\\R")) {
            if ((line.startsWith("   ") || line.startsWith("  *")) && line.contains("total")) rows++;
        }
        return rows;
    }

    @Test
    void malformedArgumentsPrintUsageAndNoSaveExplainsItself() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        SessionRuntime unsaved = SessionRuntime.start(agent(), null, workspace,
                "model", "instructions", null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        StatsCommands.handle(unsaved, "extra args", workspace, Ansi.of(false), out);
        assertEquals("Usage: /stats", lines(bytes).get(0));

        StatsCommands.handle(unsaved, "", workspace, Ansi.of(false), out);
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("session (unsaved --no-save): 0 in · 0 out · 0 total"));
        assertTrue(output.contains("No per-session breakdown yet."));
    }

    @Test
    void cappedAllTimeScanSaysMostRecentOfTotalInsteadOfImplyingCompleteness() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        int createdSessions = StatsCommands.ALL_TIME_SCAN_LIMIT + 1;
        for (int index = 0; index < createdSessions; index++) {
            SessionStore.Snapshot created = store.create(workspace, "model", "system");
            store.update(created, created.input(), created.instructions(), 1, 0);
        }
        SessionRuntime session = SessionRuntime.start(agent(), store, workspace,
                "model", "instructions", null);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StatsCommands.handle(session, "", workspace, Ansi.of(false),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        // The runtime's own session pushes the total one past the store count.
        assertTrue(output.contains("all-time across the most recent "
                        + StatsCommands.ALL_TIME_SCAN_LIMIT + " of "
                        + (createdSessions + 1) + " saved sessions"),
                output);
        assertFalse(output.contains("all-time across " + (createdSessions + 1) + " saved session"),
                "the capped line must not imply completeness");
    }

    @Test
    void breakdownErrorsReportBestEffortUnavailability() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionRuntime session = SessionRuntime.start(agent(), new SessionStore(json, state),
                workspace, "model", "instructions", null);
        // The workspace-scoped listing needs the directory; removing it makes
        // only the breakdown fail while the all-time scan keeps working.
        Files.delete(workspace);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StatsCommands.handle(session, "", workspace, Ansi.of(false),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("per-session breakdown unavailable"), output);
        assertFalse(output.contains("No per-session breakdown yet."),
                "an errored breakdown must not claim there are no sessions");
        assertTrue(output.contains("session " + session.id()), "totals still print");
    }

    private java.util.List<String> lines(ByteArrayOutputStream bytes) {
        return java.util.List.of(bytes.toString(StandardCharsets.UTF_8).split("\\R"));
    }

    private Agent agent() {
        return new Agent(json, (input, tools, instructions) -> {
            ObjectNode response = json.createObjectNode().put("status", "completed");
            response.putObject("usage").put("input_tokens", 50).put("output_tokens", 5);
            ObjectNode message = response.putArray("output").addObject();
            message.put("type", "message").put("role", "assistant");
            message.putArray("content").addObject().put("type", "output_text").put("text", "ok");
            return response;
        }, List.of(), (tool, arguments) -> false,
                new PrintStream(PrintStream.nullOutputStream()), 1, "instructions");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }

    @Test
    void shellDispatchAndRawEntrypointDispatchStats() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("dispatch-workspace"));
        Path state = temporary.resolve("dispatch-state");
        SessionRuntime session = SessionRuntime.start(agent(), new SessionStore(json, state),
                workspace, "model", "instructions", null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
        AgentConfig config = new AgentConfig("key", "http://127.0.0.1", "model", workspace, 1,
                PermissionMode.ASK);
        try (McpRuntime mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"))) {
            InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp, state,
                    new java.io.ByteArrayInputStream(new byte[0]), out, out, Ansi.of(false), approval,
                    "test", System::nanoTime);
            shell.dispatch("/stats");
            shell.dispatch("/stats now");
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(": 0 in · 0 out · 0 total"));
        assertTrue(output.contains("Usage: /stats"));

        String commands = "/stats\n/exit\n";
        ByteArrayOutputStream entrypointBytes = new ByteArrayOutputStream();
        TestRawShell.run(new String[]{"--workspace", workspace.toString(), "--session-root",
                        state.resolve("raw-entrypoint").toString()},
                Map.of("OPENAI_API_KEY", "test-key"),
                new java.io.ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(entrypointBytes, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String entrypointOutput = entrypointBytes.toString(StandardCharsets.UTF_8);
        assertTrue(entrypointOutput.contains("/stats"), "raw prompt echoes /stats");
        assertTrue(occurrences(entrypointOutput, "in · 0 out · 0 total") >= 1);
    }
}
