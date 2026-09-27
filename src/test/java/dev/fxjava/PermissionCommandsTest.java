package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCommandsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void sharedParserRemembersListsReplacesAndRevokesStableIds() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        SessionRuntime session = runtime(workspace, temporary.resolve("state"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        PermissionCommands.handle(session,
                "remember allow write_file { \"nested\": {\"b\":2, \"a\":1}, \"path\": \"a.md\" }",
                "ask", 0, Ansi.of(false), out);
        PermissionCommands.handle(session,
                "remember deny write_file {\"path\":\"a.md\",\"nested\":{\"a\":1,\"b\":2}}",
                "ask", 0, Ansi.of(false), out);
        PermissionCommands.handle(session, "", "ask", 0, Ansi.of(false), out);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Remembered allow rule 1"));
        assertTrue(output.contains("Remembered deny rule 1"), "replacement keeps the stable id");
        assertTrue(output.contains("mode=ask grants=0 rules=1"));
        assertTrue(output.contains("1  deny  write_file"));

        PermissionCommands.handle(session, "revoke 1", "ask", 0, Ansi.of(false), out);
        assertEquals(0, session.rules().count());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Revoked rule 1."));
    }

    @Test
    void malformedCommandsPrintUsageAndNoSaveRefusesMutations() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        SessionRuntime unsaved = SessionRuntime.start(agent(), null, workspace, "model", "instructions", null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        PermissionCommands.handle(unsaved, "remember allow tool {}", "ask", 0, Ansi.of(false), out);
        PermissionCommands.handle(unsaved, "remember maybe tool {}", "ask", 0, Ansi.of(false), out);
        PermissionCommands.handle(unsaved, "revoke nope", "ask", 0, Ansi.of(false), out);
        PermissionCommands.handle(unsaved, "unknown", "ask", 0, Ansi.of(false), out);

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, occurrences(output, "Session persistence is disabled by --no-save."));
        assertTrue(output.contains("Usage: /permissions remember"));
        assertTrue(output.contains("Usage: /permissions revoke"));
        assertTrue(output.contains("Usage: /permissions [ask|auto|yolo"));
        assertFalse(output.contains("Remembered "));
    }

    @Test
    void modeSelectorChangesActiveModeAndBareCommandShowsChoices() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("mode-workspace"));
        SessionRuntime session = runtime(workspace, temporary.resolve("mode-state"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        AtomicReference<PermissionMode> mode = new AtomicReference<>(PermissionMode.ASK);

        PermissionCommands.handle(session, "yolo", "ask", 0, Ansi.of(false), out, mode::set);
        PermissionCommands.handle(session, "", "yolo", 0, Ansi.of(false), out, mode::set);
        PermissionCommands.handle(session, "ask", "yolo", 0, Ansi.of(false), out, mode::set);

        assertEquals(PermissionMode.ASK, mode.get());
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("YOLO enabled: all tool approvals and remembered permission rules are bypassed"));
        assertTrue(output.contains("mode=yolo grants=0 rules=0"));
        assertTrue(output.contains("Usage: /permissions [ask|auto|yolo"));
        assertTrue(output.contains("Permission mode set to ask."));
    }

    @Test
    void failedSaveDoesNotPublishRememberOrRevokeInMemory() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionRuntime session = runtime(workspace, state);
        SessionRuntime stale = SessionRuntime.start(agent(), new SessionStore(json, state), workspace,
                "model", "instructions", session.id());
        String key = SessionRules.normalizeArguments(json.readTree("{\"path\":\"a.md\"}"));
        session.rememberRule(SessionRules.Kind.ALLOW, "write_file", key);

        assertThrows(IOException.class,
                () -> stale.rememberRule(SessionRules.Kind.DENY, "write_file", key));
        assertEquals(SessionRules.Decision.UNRESOLVED, stale.rules().decide("write_file", key));
        assertEquals(SessionRules.Decision.ALLOW, session.rules().decide("write_file", key));
        assertEquals(SessionRules.Decision.ALLOW,
                new SessionStore(json, state).load(session.id()).rules().decide("write_file", key));
    }

    @Test
    void rawShellDispatchParsesNestedPermissionCommands() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionRuntime session = runtime(workspace, state);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
        AgentConfig config = new AgentConfig("key", "http://127.0.0.1", "model", workspace, 1,
                PermissionMode.ASK);
        try (McpRuntime mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"))) {
            InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp, state,
                    new ByteArrayInputStream(new byte[0]), out, out, Ansi.of(false), approval,
                    "test", System::nanoTime);
            shell.dispatch("/permissions\tremember\tdeny write_file {\"path\":\"raw.md\"}");
            shell.dispatch("/permissions");
            shell.dispatch("/permissions yolo");
            assertEquals(PermissionMode.YOLO, approval.permissionMode());
            shell.dispatch("/permissions ask");
            assertEquals(PermissionMode.ASK, approval.permissionMode());
            shell.dispatch("/permissions revoke 1");
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Remembered deny rule 1"));
        assertTrue(output.contains("mode=ask grants=0 rules=1"));
        assertTrue(output.contains("YOLO enabled: all tool approvals"));
        assertTrue(output.contains("Permission mode set to ask."));
        assertTrue(output.contains("Revoked rule 1."));
    }

    @Test
    void rawSessionTransitionsClearGrantsOnlyAfterSuccess() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("transition-workspace"));
        Path state = temporary.resolve("transition-state");
        SessionRuntime session = runtime(workspace, state);
        String firstId = session.id();
        SessionApprovals grants = new SessionApprovals();
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false, grants);
        AgentConfig config = new AgentConfig("key", "http://127.0.0.1", "model", workspace, 1,
                PermissionMode.ASK);
        try (McpRuntime mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"))) {
            InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp, state,
                    new ByteArrayInputStream(new byte[0]), new PrintStream(PrintStream.nullOutputStream()),
                    new PrintStream(PrintStream.nullOutputStream()), Ansi.of(false), approval,
                    "test", System::nanoTime);

            grants.grant("write_file", "one");
            shell.dispatch("/new");
            assertEquals(0, approval.grantCount());

            grants.grant("write_file", "two");
            shell.dispatch("/resume " + firstId);
            assertEquals(0, approval.grantCount());

            grants.grant("write_file", "three");
            shell.dispatch("/recover " + firstId);
            assertEquals(0, approval.grantCount());

            grants.grant("write_file", "retained");
            assertThrows(IOException.class, () -> shell.dispatch("/resume missing-session"));
            assertEquals(1, approval.grantCount(), "a failed transition retains the active session grant");
        }
    }

    @Test
    void legacyShellParsesRememberListRevokeAndMalformedInput() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        String commands = "/permissions\tremember\tallow write_file {\"path\":\"legacy.md\"}\n"
                + "/permissions\n/permissions yolo\n/permissions\n/permissions ask\n"
                + "/permissions revoke 1\n/permissions remember deny write_file nope\n/exit\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = Main.run(new String[]{"--workspace", workspace.toString(), "--session-root", state.toString()},
                Map.of("OPENAI_API_KEY", "test-key"),
                new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit);
        assertTrue(output.contains("Remembered allow rule 1"));
        assertTrue(output.contains("mode=ask grants=0 rules=1"));
        assertTrue(output.contains("YOLO enabled: all tool approvals"));
        assertTrue(output.contains("mode=yolo grants=0 rules=1"));
        assertTrue(output.contains("Permission mode set to ask."));
        assertTrue(output.contains("Revoked rule 1."));
        assertTrue(output.contains("Usage: /permissions remember"));
    }

    @Test
    void startupYoloCanBeLoweredBackToAskAndApprovalChecksReturn() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("startup-yolo-workspace"));
        Path state = temporary.resolve("startup-yolo-state");
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String commands = "/permissions\n/permissions ask\n/exit\n";
        int exit = Main.run(new String[]{"--yolo", "--workspace", workspace.toString(),
                        "--session-root", state.toString()}, Map.of("OPENAI_API_KEY", "test-key"),
                new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(PrintStream.nullOutputStream()), approval);

        assertEquals(0, exit);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("mode=yolo"));
        assertEquals(PermissionMode.ASK, approval.permissionMode());
        Tool guarded = new Tool() {
            @Override public String name() { return "guarded"; }
            @Override public String description() { return "guarded test action"; }
            @Override public com.fasterxml.jackson.databind.node.ObjectNode parameters() {
                return json.createObjectNode();
            }
            @Override public boolean requiresApproval() { return true; }
            @Override public String preview(com.fasterxml.jackson.databind.JsonNode arguments) { return "guarded"; }
            @Override public String execute(com.fasterxml.jackson.databind.JsonNode arguments) { return "ok"; }
        };
        assertFalse(approval.approve(guarded, json.createObjectNode()),
                "the original ask fallback is active again after leaving startup YOLO");
    }

    @Test
    void legacySessionTransitionsClearGrantsOnlyAfterSuccess() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("legacy-transition-workspace"));
        Path state = temporary.resolve("legacy-transition-state");
        SessionApprovals grants = new SessionApprovals();
        grants.grant("write_file", "legacy");
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false, grants);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String commands = "/permissions\n/new\n/permissions\n/exit\n";
        int exit = Main.run(new String[]{"--workspace", workspace.toString(),
                        "--session-root", state.toString()}, Map.of("OPENAI_API_KEY", "test-key"),
                new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new PrintStream(PrintStream.nullOutputStream()), approval);
        assertEquals(0, exit);
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("mode=ask grants=1 rules=0"));
        assertTrue(output.contains("mode=ask grants=0 rules=0"));

        SessionApprovals retained = new SessionApprovals();
        retained.grant("write_file", "legacy-failed");
        ApprovalRouter failedApproval = new ApprovalRouter((tool, arguments) -> false, retained);
        assertThrows(IOException.class, () -> Main.run(new String[]{"--workspace", workspace.toString(),
                        "--session-root", state.toString()}, Map.of("OPENAI_API_KEY", "test-key"),
                new ByteArrayInputStream("/resume missing-session\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(PrintStream.nullOutputStream()),
                new PrintStream(PrintStream.nullOutputStream()), failedApproval));
        assertEquals(1, failedApproval.grantCount(),
                "legacy failed transition retains the current session grant");
    }

    @Test
    void renameRefreshesLocalRulesFromAnotherProcessWrite() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        SessionStore store = new SessionStore(json, state);
        SessionStore.Snapshot created = store.create(workspace, "model", "instructions");
        // Two runtimes over the same store stand in for two processes.
        SessionRuntime writer = SessionRuntime.start(agent(), store, workspace,
                "model", "instructions", created.id());
        SessionRuntime renamer = SessionRuntime.start(agent(), store, workspace,
                "model", "instructions", created.id());
        assertEquals(0, renamer.rules().count());

        assertNotNull(writer.rememberRule(SessionRules.Kind.ALLOW, "write_file",
                SessionRules.normalizeArguments(json.readTree("{\"path\":\"a.md\"}"))));
        assertEquals(0, renamer.rules().count(),
                "the stale runtime has not seen the other process's rule yet");

        renamer.rename("renamed across processes");

        assertEquals(1, renamer.rules().count(),
                "rename adopts the authoritative rules the store returns");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PermissionCommands.handle(renamer, "", "ask", 0, Ansi.of(false),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("rules=1"), output);
        assertTrue(output.contains("write_file"), output);
    }

    private SessionRuntime runtime(Path workspace, Path state) throws IOException {
        return SessionRuntime.start(agent(), new SessionStore(json, state), workspace,
                "model", "instructions", null);
    }

    private Agent agent() {
        return new Agent(json, (input, tools, instructions) -> json.createObjectNode(), List.of(),
                (tool, arguments) -> false, new PrintStream(PrintStream.nullOutputStream()), 1, "instructions");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) count++;
        return count;
    }
}
