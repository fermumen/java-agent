package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Embedded permission state follows the authoritative session snapshot lifecycle. */
class SessionRulesStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path root;

    private SessionStore store() throws IOException {
        return new SessionStore(json, root);
    }

    @Test
    void rulesSurviveRestartResumeAndContinueStableIds() throws IOException {
        SessionRuntime first = SessionRuntime.start(agent(), store(), root, "model", "instructions", null);
        String sessionId = first.id();
        assertEquals("1", first.rememberRule(SessionRules.Kind.DENY, "write_file", key("notes.md")));
        assertEquals("2", first.rememberRule(SessionRules.Kind.ALLOW, "run_command", "{}"));

        SessionRuntime resumed = SessionRuntime.start(agent(), store(), root,
                "model", "instructions", sessionId);
        assertEquals(SessionRules.Decision.DENY, resumed.rules().decide("write_file", key("notes.md")));
        assertEquals("3", resumed.rememberRule(SessionRules.Kind.ALLOW, "open_file", key("b.md")));
        assertEquals(3, store().load(sessionId).rules().count());
    }

    @Test
    void newSessionIsExplicitlyEmptyAndResumeDoesNotLeakRules() throws IOException {
        SessionRuntime runtime = SessionRuntime.start(agent(), store(), root, "model", "instructions", null);
        String original = runtime.id();
        runtime.rememberRule(SessionRules.Kind.DENY, "write_file", key("notes.md"));

        runtime.newSession(root, "model", "instructions");
        assertEquals(0, runtime.rules().count());
        runtime.resume(original, root);
        assertEquals(SessionRules.Decision.DENY, runtime.rules().decide("write_file", key("notes.md")));
    }

    @Test
    void conversationRenameAndReconfigurePreserveEmbeddedRules() throws IOException {
        SessionStore sessions = store();
        SessionStore.Snapshot snapshot = sessions.create(root, "model", "instructions");
        snapshot = remember(sessions, snapshot, SessionRules.Kind.DENY, "write_file", key("notes.md"));
        ArrayNode input = json.createArrayNode();
        input.addObject().put("role", "user").put("content", "hello");

        snapshot = sessions.update(snapshot, input, "updated");
        snapshot = sessions.rename(snapshot, "named");
        snapshot = sessions.reconfigure(snapshot, "model-2", input, "configured");

        SessionStore.Snapshot loaded = sessions.load(snapshot.id());
        assertEquals("named", loaded.title());
        assertEquals("model-2", loaded.model());
        assertEquals(SessionRules.Decision.DENY, loaded.rules().decide("write_file", key("notes.md")));
    }

    @Test
    void ordinaryPersistUsesCurrentDiskRulesInsteadOfStaleSnapshotRules() throws IOException {
        SessionStore first = store();
        SessionStore.Snapshot staleConversation = first.create(root, "model", "instructions");
        SessionStore.Snapshot current = remember(store(), staleConversation,
                SessionRules.Kind.DENY, "write_file", key("notes.md"));

        ArrayNode input = json.createArrayNode();
        input.addObject().put("role", "user").put("content", "new");
        SessionStore.Snapshot persisted = first.update(staleConversation, input, "instructions");
        assertEquals(current.rules().all(), persisted.rules().all());
        assertEquals(current.rules().all(), first.load(current.id()).rules().all());
    }

    @Test
    void recoveryCopiesRulesAndPublishesLatestLast() throws IOException {
        SessionStore sessions = store();
        SessionStore.Snapshot source = sessions.create(root, "model", "instructions");
        source = remember(sessions, source, SessionRules.Kind.DENY, "write_file", key("notes.md"));

        SessionStore.Snapshot recovered = sessions.recover(source.id());
        assertNotEquals(source.id(), recovered.id());
        assertEquals(source.rules().all(), recovered.rules().all());
        assertEquals(recovered.id(), sessions.latest(root).id());
    }

    @Test
    void providerIndependentPersistenceUsesOnlyOrdinarySnapshotOperations() throws IOException {
        SessionStore sessions = store();
        SessionStore.Snapshot snapshot = sessions.create(root, "model", "instructions");
        snapshot = remember(sessions, snapshot, SessionRules.Kind.ALLOW, "write_file", key("a.md"));
        assertEquals(1, sessions.load(snapshot.id()).rules().count());
        assertTrue(json.readTree(Files.readString(root.resolve("sessions").resolve(snapshot.id())
                .resolve("session.json"))).path("permission_state").isObject());
    }

    @Test
    void concurrentRuntimeMutationsSerializeAndPersist() throws Exception {
        SessionRuntime runtime = SessionRuntime.start(agent(), store(), root, "model", "instructions", null);
        List<Callable<String>> mutations = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            int value = index;
            mutations.add(() -> runtime.rememberRule(SessionRules.Kind.DENY, "tool_" + value, "{}"));
        }
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (var result : executor.invokeAll(mutations)) assertTrue(result.get() != null);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(24, runtime.rules().count());
        assertEquals(24, store().load(runtime.id()).rules().count());
    }

    @Test
    void staleWriterFailureLeavesLiveAndDiskUnchanged() throws IOException {
        SessionRuntime first = SessionRuntime.start(agent(), store(), root, "model", "instructions", null);
        SessionRuntime stale = SessionRuntime.start(agent(), store(), root, "model", "instructions", first.id());
        first.rememberRule(SessionRules.Kind.DENY, "first", "{}");

        assertThrows(IOException.class, () -> stale.rememberRule(SessionRules.Kind.DENY, "stale", "{}"));
        assertEquals(0, stale.rules().count());
        SessionRules persisted = store().load(first.id()).rules();
        assertEquals(SessionRules.Decision.DENY, persisted.decide("first", "{}"));
        assertEquals(SessionRules.Decision.UNRESOLVED, persisted.decide("stale", "{}"));
    }

    @Test
    void failedRecoveryPublishesNeitherSessionNorLatest() throws IOException {
        SessionStore failing = new SessionStore(json, root, java.time.Clock.systemUTC(),
                () -> { throw new IOException("injected snapshot failure"); });
        SessionStore.Snapshot source = failing.create(root, "model", "instructions");
        source = remember(failing, source, SessionRules.Kind.DENY, "write_file", key("notes.md"));
        String sourceId = source.id();
        String latestBefore = failing.latest(root).id();

        IOException failure = assertThrows(IOException.class, () -> failing.recover(sourceId));
        assertTrue(failure.getMessage().contains("injected snapshot failure"));
        assertEquals(latestBefore, failing.latest(root).id());
        assertEquals(List.of(sourceId), failing.list(root, 10).stream()
                .map(SessionStore.Snapshot::id).collect(java.util.stream.Collectors.toList()));
        try (var entries = Files.newDirectoryStream(root.resolve("sessions"))) {
            for (Path entry : entries) {
                assertFalse(entry.getFileName().toString().startsWith(".recover-"));
            }
        }
    }

    private SessionStore.Snapshot remember(SessionStore sessions, SessionStore.Snapshot snapshot,
                                           SessionRules.Kind kind, String tool, String arguments) throws IOException {
        SessionRules candidate = snapshot.rules();
        long expected = candidate.generation();
        candidate.remember(kind, tool, arguments);
        return sessions.updateRules(snapshot, candidate, expected);
    }

    private String key(String path) {
        return SessionRules.normalizeArguments(json.createObjectNode().put("path", path));
    }

    private Agent agent() {
        return new Agent(json, (input, tools, instructions) -> json.createObjectNode(), List.of(),
                (tool, arguments) -> false, new PrintStream(PrintStream.nullOutputStream()), 1, "instructions");
    }
}
