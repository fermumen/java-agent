package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultSessionLifecycleTest {
    private static final Pattern HANDLE = Pattern.compile("<tool_result_handle>([^<]+)</tool_result_handle>");

    @TempDir
    Path root;

    @Test
    void recoveryCopiesOnlyReferencedAuthenticatedResultSidecars() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        ObjectMapper json = new ObjectMapper();
        SessionStore sessions = new SessionStore(json, root.resolve("state"));
        SessionStore.Snapshot source = sessions.create(workspace, "model", "instructions");
        ToolResultStore results = new ToolResultStore(root.resolve("state"));
        results.setSession(source.id());
        String prepared = results.prepare("call", "large", "needle\n" + "x".repeat(20_000));
        Matcher matcher = HANDLE.matcher(prepared);
        assertTrue(matcher.find());
        String handle = matcher.group(1);
        String orphaned = results.prepare("orphan", "large", "orphan\n" + "y".repeat(20_000));
        Matcher orphanMatcher = HANDLE.matcher(orphaned);
        assertTrue(orphanMatcher.find());
        String orphanHandle = orphanMatcher.group(1);
        ArrayNode input = json.createArrayNode();
        input.addObject().put("type", "function_call_output").put("call_id", "call").put("output", prepared);
        source = sessions.update(source, input, "instructions");
        var manifest = json.readTree(Files.readString(root.resolve("state/sessions")
                .resolve(source.id()).resolve("session.json"))).path("artifacts");
        assertTrue(manifest.path("images").isEmpty());
        assertTrue(manifest.path("tool_results").isArray());
        assertTrue(manifest.path("tool_results").size() == 1);
        assertTrue(manifest.path("tool_results").path(0).asText().equals(handle));

        SessionStore.Snapshot recovered = sessions.recover(source.id());
        results.setSession(recovered.id());
        assertTrue(results.read(handle, 1, 32, null).contains("needle"));
        assertThrows(Exception.class, () -> results.read(orphanHandle, 1, 32, null));

        Path recoveredDirectory = root.resolve("state").resolve("sessions").resolve(recovered.id());
        sessions.delete(recovered.id());
        assertFalse(Files.exists(recoveredDirectory));
        results.setSession(source.id());
        assertTrue(results.read(handle, 1, 32, null).contains("needle"));
    }

    @Test
    void manifestRejectsMissingAndTamperedReferencedResults() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("tamper-workspace"));
        ObjectMapper json = new ObjectMapper();
        Path state = root.resolve("tamper-state");
        SessionStore sessions = new SessionStore(json, state);
        SessionStore.Snapshot source = sessions.create(workspace, "model", "instructions");
        ToolResultStore results = new ToolResultStore(state);
        results.setSession(source.id());
        String prepared = results.prepare("call", "large", "needle\n" + "x".repeat(20_000));
        Matcher matcher = HANDLE.matcher(prepared);
        assertTrue(matcher.find());
        String handle = matcher.group(1);
        ArrayNode input = json.createArrayNode();
        input.addObject().put("type", "function_call_output").put("call_id", "call").put("output", prepared);
        source = sessions.update(source, input, "instructions");
        String sourceId = source.id();

        Path sidecar = state.resolve("sessions").resolve(sourceId).resolve("tool-results").resolve(handle);
        Files.writeString(sidecar, "tampered");
        assertTrue(assertThrows(Exception.class, () -> sessions.load(sourceId))
                .getMessage().contains("digest mismatch"));
        assertThrows(Exception.class, () -> sessions.recover(sourceId));

        Files.delete(sidecar);
        assertTrue(assertThrows(Exception.class, () -> sessions.load(sourceId))
                .getMessage().contains("Missing session tool result"));
    }
}
