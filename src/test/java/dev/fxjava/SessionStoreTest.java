package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compact port of fx session layout, latest-pointer, catalog, and recovery contracts. */
class SessionStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    private Path workspaceA;
    private Path workspaceB;
    private MutableClock clock;
    private SessionStore store;

    @BeforeEach
    void createStore() throws IOException {
        workspaceA = Files.createDirectory(temporary.resolve("workspace-a"));
        workspaceB = Files.createDirectory(temporary.resolve("workspace-b"));
        clock = new MutableClock(1_700_000_000_000L);
        store = new SessionStore(json, temporary.resolve("state"), clock);
    }

    @Test
    void createPersistsSafeIdentityAndWorkspaceLatestPointer() throws Exception {
        SessionStore.Snapshot created = store.create(workspaceA, "gpt-5.6", "system");
        assertTrue(created.id().matches("[0-9]{8}-[0-9]{6}-[a-f0-9]{12}"));
        assertEquals(workspaceA.toRealPath().toString(), created.workspace());
        assertEquals(created, store.load(created.id()));
        assertEquals(created.id(), store.latest(workspaceA).id());
        assertTrue(Files.isRegularFile(temporary.resolve("state/sessions")
                .resolve(created.id()).resolve("session.json")));
    }

    @Test
    void inputRoundTripsWithDeepCopyOwnership() throws Exception {
        SessionStore.Snapshot created = store.create(workspaceA, "gpt-5.6", "system");
        ArrayNode history = json.createArrayNode();
        history.addObject().put("role", "user").put("content", "hello");
        SessionStore.Snapshot updated = store.update(created, history, "updated system");
        history.removeAll();

        SessionStore.Snapshot loaded = store.load(created.id());
        assertEquals("hello", loaded.input().path(0).path("content").asText());
        ArrayNode callerCopy = loaded.input();
        callerCopy.removeAll();
        assertEquals(1, loaded.input().size());
        assertEquals("updated system", updated.instructions());
    }

    @Test
    void schemaV1LoadsAndRewritesAsSchemaV2WithArtifactManifest() throws Exception {
        SessionStore.Snapshot created = store.create(workspaceA, "gpt-5.6", "system");
        Path file = temporary.resolve("state/sessions").resolve(created.id()).resolve("session.json");
        ObjectNode legacy = (ObjectNode) json.readTree(Files.readString(file));
        legacy.put("schema_version", 1);
        legacy.remove("artifacts");
        Files.writeString(file, json.writeValueAsString(legacy), StandardCharsets.UTF_8);

        SessionStore.Snapshot loaded = store.load(created.id());
        assertEquals(created, loaded);
        assertEquals(1, json.readTree(Files.readString(file)).path("schema_version").asInt());

        store.save(loaded);
        ObjectNode migrated = (ObjectNode) json.readTree(Files.readString(file));
        assertEquals(2, migrated.path("schema_version").asInt());
        assertTrue(migrated.path("artifacts").path("images").isArray());
        assertTrue(migrated.path("artifacts").path("tool_results").isArray());
        assertEquals(0, migrated.path("artifacts").path("images").size());
        assertEquals(0, migrated.path("artifacts").path("tool_results").size());
    }

    @Test
    void rejectsUnknownFutureSchemaWithoutMutatingIt() throws Exception {
        SessionStore.Snapshot created = store.create(workspaceA, "gpt-5.6", "system");
        Path file = temporary.resolve("state/sessions").resolve(created.id()).resolve("session.json");
        ObjectNode future = (ObjectNode) json.readTree(Files.readString(file));
        future.put("schema_version", 99);
        String bytes = json.writeValueAsString(future);
        Files.writeString(file, bytes, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store.load(created.id()));
        assertEquals(bytes, Files.readString(file));
    }

    @Test
    void latestPointersRemainIndependentAcrossWorkspaces() throws Exception {
        SessionStore.Snapshot firstA = store.create(workspaceA, "model-a", "a");
        clock.advance(1_000);
        SessionStore.Snapshot firstB = store.create(workspaceB, "model-b", "b");
        clock.advance(1_000);
        SessionStore.Snapshot secondA = store.create(workspaceA, "model-a2", "a2");

        assertEquals(secondA.id(), store.latest(workspaceA).id());
        assertEquals(firstB.id(), store.latest(workspaceB).id());
        assertNotEquals(firstA.id(), store.latest(workspaceA).id());
    }

    @Test
    void catalogIsWorkspaceFilteredNewestFirstAndBounded() throws Exception {
        SessionStore.Snapshot first = store.create(workspaceA, "one", "system");
        clock.advance(10);
        SessionStore.Snapshot other = store.create(workspaceB, "other", "system");
        clock.advance(10);
        SessionStore.Snapshot latest = store.create(workspaceA, "two", "system");

        assertEquals(List.of(latest.id(), first.id()),
                store.list(workspaceA, 10).stream().map(SessionStore.Snapshot::id)
                        .collect(Collectors.toList()));
        assertEquals(List.of(latest.id()),
                store.list(workspaceA, 1).stream().map(SessionStore.Snapshot::id)
                        .collect(Collectors.toList()));
        assertEquals(3, store.list(null, 10).size());
        assertTrue(store.list(null, 10).stream().anyMatch(value -> value.id().equals(other.id())));
    }

    @Test
    void corruptSnapshotIsIsolatedAndLatestRepairsToValidPredecessor() throws Exception {
        SessionStore.Snapshot valid = store.create(workspaceA, "valid", "system");
        clock.advance(10);
        SessionStore.Snapshot corrupt = store.create(workspaceA, "corrupt", "system");
        Path corruptFile = temporary.resolve("state/sessions").resolve(corrupt.id()).resolve("session.json");
        Files.writeString(corruptFile, "{broken", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store.load(corrupt.id()));
        assertEquals(List.of(valid.id()),
                store.list(workspaceA, 10).stream().map(SessionStore.Snapshot::id)
                        .collect(Collectors.toList()));
        assertEquals(valid.id(), store.latest(workspaceA).id());
        assertEquals("{broken", Files.readString(corruptFile));
    }

    @Test
    void recoveryCreatesIndependentCopyWithoutChangingSource() throws Exception {
        SessionStore.Snapshot source = store.create(workspaceA, "model", "system");
        ArrayNode input = json.createArrayNode();
        input.addObject().put("role", "user").put("content", "recover me");
        source = store.update(source, input, "system");
        String sourceBytes = Files.readString(temporary.resolve("state/sessions")
                .resolve(source.id()).resolve("session.json"));
        clock.advance(100);

        SessionStore.Snapshot recovered = store.recover(source.id());
        assertNotEquals(source.id(), recovered.id());
        assertEquals(source.input(), recovered.input());
        assertEquals(sourceBytes, Files.readString(temporary.resolve("state/sessions")
                .resolve(source.id()).resolve("session.json")));
        assertEquals(recovered.id(), store.latest(workspaceA).id());
    }

    @Test
    void unsafeIdsAndSymlinkedManagedPathsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("C:\\escape"));

        Path external = Files.createDirectory(temporary.resolve("external"));
        Path linked = temporary.resolve("state/sessions/linked");
        try {
            Files.createSymbolicLink(linked, external);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        assertThrows(IOException.class, () -> store.load("linked"));
    }

    @Test
    void renamePersistsBoundedTitleAndAtomicWritesLeaveNoTemps() throws Exception {
        SessionStore.Snapshot snapshot = store.create(workspaceA, "model", "system");
        SessionStore.Snapshot renamed = store.rename(snapshot, "  useful session  ");
        assertEquals("useful session", store.load(snapshot.id()).title());
        assertEquals("useful session", renamed.title());
        assertThrows(IllegalArgumentException.class, () -> store.rename(snapshot, "x".repeat(201)));

        try (var paths = Files.walk(temporary.resolve("state"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".session-")));
        }
    }

    @Test
    void concurrentStoreInstancesSerializeMutationsWithoutCorruption() throws Exception {
        SessionStore.Snapshot snapshot = store.create(workspaceA, "model", "system");
        var tasks = new java.util.ArrayList<Callable<Void>>();
        for (int index = 0; index < 16; index++) {
            int value = index;
            tasks.add(() -> {
                SessionStore writer = new SessionStore(json, temporary.resolve("state"));
                ArrayNode input = json.createArrayNode();
                input.addObject().put("role", "user").put("content", "writer-" + value);
                writer.update(snapshot, input, "system-" + value);
                return null;
            });
        }
        var pool = Executors.newFixedThreadPool(8);
        try {
            for (var future : pool.invokeAll(tasks)) future.get();
        } finally {
            pool.shutdownNow();
        }
        SessionStore.Snapshot loaded = store.load(snapshot.id());
        assertEquals(1, loaded.input().size());
        assertTrue(loaded.input().path(0).path("content").asText().startsWith("writer-"));
        assertTrue(Files.isRegularFile(temporary.resolve("state/sessions.lock")));
        try (var paths = Files.walk(temporary.resolve("state"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".session-")));
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
