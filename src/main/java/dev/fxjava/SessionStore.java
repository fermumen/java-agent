package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Compact, atomic persisted-session store derived from fx's durable core contracts. */
public final class SessionStore {
    private static final int LEGACY_SCHEMA_VERSION = 1;
    private static final int ARTIFACT_SCHEMA_VERSION = 2;
    private static final int SCHEMA_VERSION = 3;
    private static final int MAX_ARTIFACT_REFERENCES = 512;
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final Object JVM_MUTATION_LOCK = new Object();
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper json;
    private final Path root;
    private final Path sessions;
    private final Path latest;
    private final Clock clock;
    private final RecoveryFailpoint recoveryFailpoint;

    public SessionStore(ObjectMapper json, Path root) throws IOException {
        this(json, root, Clock.systemUTC());
    }

    SessionStore(ObjectMapper json, Path root, Clock clock) throws IOException {
        this(json, root, clock, true, RecoveryFailpoint.NONE);
    }

    SessionStore(ObjectMapper json, Path root, Clock clock, RecoveryFailpoint recoveryFailpoint)
            throws IOException {
        this(json, root, clock, true, recoveryFailpoint);
    }

    private SessionStore(ObjectMapper json, Path root, Clock clock, boolean create,
                         RecoveryFailpoint recoveryFailpoint) throws IOException {
        this.json = json;
        this.root = root.toAbsolutePath().normalize();
        this.sessions = this.root.resolve("sessions");
        this.latest = this.root.resolve("latest");
        this.clock = clock;
        this.recoveryFailpoint = recoveryFailpoint;
        if (create) {
            createManagedDirectory(this.root);
            createManagedDirectory(this.sessions);
            createManagedDirectory(this.latest);
        }
    }

    static SessionStore inspect(ObjectMapper json, Path root) throws IOException {
        return new SessionStore(json, root, Clock.systemUTC(), false, RecoveryFailpoint.NONE);
    }

    public static Path defaultRoot() {
        String configured = System.getenv("JAVA_AGENT_HOME");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".java-agent");
    }

    public Snapshot create(Path workspace, String model, String instructions) throws IOException {
        long now = clock.millis();
        Snapshot snapshot = new Snapshot(newId(now), canonicalWorkspace(workspace), model, "", instructions,
                now, now, json.createArrayNode(), new SessionRules(), SessionUsage.zeroed());
        save(snapshot);
        return snapshot;
    }

    public void save(Snapshot snapshot) throws IOException {
        validate(snapshot);
        locked(() -> {
            Path directory = sessionDirectory(snapshot.id());
            createManagedDirectory(directory);
            Snapshot effective = snapshot;
            if (Files.exists(directory.resolve("session.json"), LinkOption.NOFOLLOW_LINKS)) {
                Snapshot persisted = load(snapshot.id());
                effective = snapshot.withRules(persisted.rules()).withUsage(persisted.usage());
            }
            writeSnapshotUnlocked(effective, directory, false);
            writeLatestUnlocked(effective.workspace(), effective.id());
        });
    }

    public Snapshot load(String id) throws IOException {
        Path file = sessionFile(id);
        rejectSymlink(file.getParent());
        rejectSymlink(file);
        long size = Files.size(file);
        if (size > MAX_SNAPSHOT_BYTES) throw new IOException("Session snapshot exceeds 8 MiB: " + id);
        JsonNode node;
        try (com.fasterxml.jackson.core.JsonParser parser = json.getFactory().createParser(Files.readAllBytes(file))) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            node = json.readTree(parser);
            if (parser.nextToken() != null) throw new IOException("Trailing data in session snapshot: " + id);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException parseFailure) {
            throw new IOException("Corrupt session snapshot: " + id, parseFailure);
        }
        Decoded decoded = decode(id, node);
        if (decoded.artifacts() != null) {
            decoded.artifacts().validateAgainst(decoded.snapshot().input());
            ToolResultStore.verifySidecars(file.getParent(), decoded.artifacts().toolResults());
        }
        Snapshot snapshot = decoded.snapshot();
        ArrayNode hydrated = SessionImages.hydrate(file.getParent(), snapshot.input());
        return new Snapshot(snapshot.id(), snapshot.workspace(), snapshot.model(), snapshot.title(),
                snapshot.instructions(), snapshot.createdAt(), snapshot.updatedAt(), hydrated,
                snapshot.rules(), snapshot.usage());
    }

    public Snapshot latest(Path workspace) throws IOException {
        String canonical = canonicalWorkspace(workspace);
        Path pointer = latest.resolve(workspaceKey(canonical) + ".txt");
        if (Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(pointer)) {
            String id = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            try {
                Snapshot snapshot = load(id);
                if (snapshot.workspace().equals(canonical)) return snapshot;
            } catch (IOException | IllegalArgumentException ignored) {
                // Repair below from canonical snapshots.
            }
        }
        Snapshot fallback = list(workspace, Integer.MAX_VALUE).stream().findFirst()
                .orElseThrow(() -> new IOException("No saved session for workspace: " + canonical));
        locked(() -> writeLatestUnlocked(canonical, fallback.id()));
        return fallback;
    }

    public List<Snapshot> list(Path workspace, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return listBounded(workspace, limit).sessions();
    }

    /**
     * Recency-ordered listing bounded to {@code limit}, plus the total number
     * of saved sessions found before the cap was applied so callers can say
     * "most recent N of M" instead of implying completeness.
     */
    public BoundedListing listBounded(Path workspace, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (!Files.exists(sessions, LinkOption.NOFOLLOW_LINKS)) return new BoundedListing(List.of(), 0);
        rejectSymlink(sessions);
        if (!Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Session path is not a directory: " + sessions);
        }
        String canonical = workspace == null ? null : canonicalWorkspace(workspace);
        List<Snapshot> result = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(sessions)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue;
                String id = directory.getFileName().toString();
                if (!safeId(id)) continue;
                try {
                    Snapshot snapshot = load(id);
                    if (canonical == null || snapshot.workspace().equals(canonical)) result.add(snapshot);
                } catch (IOException ignored) {
                    // Corrupt and incomplete sessions stay isolated from the catalog.
                }
            }
        }
        result.sort(Comparator.comparingLong(Snapshot::updatedAt).reversed()
                .thenComparing(Snapshot::id, Comparator.reverseOrder()));
        return new BoundedListing(
                List.copyOf(result.subList(0, Math.min(limit, result.size()))), result.size());
    }

    /** A bounded listing slice together with the pre-cap total. */
    public static final class BoundedListing {
        private final List<Snapshot> sessions;
        private final int totalFound;

        BoundedListing(List<Snapshot> sessions, int totalFound) {
            this.sessions = sessions;
            this.totalFound = totalFound;
        }

        public List<Snapshot> sessions() { return sessions; }
        public int totalFound() { return totalFound; }
    }

    public Snapshot recover(String id) throws IOException {
        return lockedSupply(() -> recoverUnlocked(id));
    }

    private Snapshot recoverUnlocked(String id) throws IOException {
        Snapshot source = load(id);
        long now = clock.millis();
        Snapshot recovered = new Snapshot(newId(now), source.workspace(), source.model(), source.title(),
                source.instructions(), now, now, source.input(), source.rules(), source.usage());
        String stagingName = ".recover-" + UUID.randomUUID();
        Path staging = sessions.resolve(stagingName);
        Path promoted = sessionDirectory(recovered.id());
        createManagedDirectory(staging);
        boolean promotedDirectory = false;
        try {
            ArrayNode persisted = SessionImages.externalize(staging, recovered.input());
            ArtifactManifest artifacts = ArtifactManifest.from(persisted);
            ToolResultStore.copySidecars(sessionDirectory(id), staging, artifacts.toolResults());
            SessionImages.verifySidecars(staging, artifacts.images());
            ToolResultStore.verifySidecars(staging, artifacts.toolResults());
            ObjectNode encoded = encode(recovered, persisted, artifacts);
            validateEncoded(recovered, encoded);
            atomicWrite(staging.resolve("session.json"), json.writeValueAsString(encoded), true);
            recoveryFailpoint.beforePromotion();
            Decoded staged = decode(recovered.id(), encoded);
            staged.artifacts().validateAgainst(staged.snapshot().input());

            if (Files.exists(promoted, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Recovered session id already exists: " + recovered.id());
            }
            try {
                Files.move(staging, promoted, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic recovered-session promotion is unsupported", unsupported);
            }
            promotedDirectory = true;
            try {
                writeLatestUnlocked(recovered.workspace(), recovered.id());
            } catch (IOException latestFailure) {
                deleteSessionDirectory(promoted);
                promotedDirectory = false;
                throw latestFailure;
            }
            return recovered;
        } finally {
            if (!promotedDirectory && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                deleteSessionDirectory(staging);
            }
        }
    }

    public Snapshot rename(Snapshot snapshot, String title) throws IOException {
        String normalized = title == null ? "" : title.trim();
        if (normalized.length() > 200) throw new IllegalArgumentException("title must be at most 200 characters");
        Snapshot renamed = new Snapshot(snapshot.id(), snapshot.workspace(), snapshot.model(), normalized,
                snapshot.instructions(), snapshot.createdAt(), clock.millis(), snapshot.input(),
                snapshot.rules(), snapshot.usage());
        return updatePreservingState(renamed, 0, 0);
    }

    public Snapshot update(Snapshot snapshot, ArrayNode input, String instructions) throws IOException {
        return update(snapshot, input, instructions, 0, 0);
    }

    /**
     * Replaces the conversation and folds usage deltas into the persisted
     * totals. Both rules and usage are re-read from the authoritative on-disk
     * snapshot under the store lock, so a turn that mutates usage while rules
     * change in the same process never loses either update.
     */
    public Snapshot update(Snapshot snapshot, ArrayNode input, String instructions,
                           long inputTokenDelta, long outputTokenDelta) throws IOException {
        return update(snapshot, input, instructions, inputTokenDelta, outputTokenDelta, snapshot.model());
    }

    Snapshot update(Snapshot snapshot, ArrayNode input, String instructions,
                    long inputTokenDelta, long outputTokenDelta, String model) throws IOException {
        if (model == null || model.isBlank() || model.length() > 200) {
            throw new IllegalArgumentException("Invalid session model");
        }
        Snapshot updated = new Snapshot(snapshot.id(), snapshot.workspace(), model, snapshot.title(),
                instructions, snapshot.createdAt(), clock.millis(), input, snapshot.rules());
        return updatePreservingState(updated, inputTokenDelta, outputTokenDelta);
    }

    Snapshot reconfigure(Snapshot snapshot, String model, ArrayNode input, String instructions) throws IOException {
        if (model == null || model.isBlank() || model.length() > 200) {
            throw new IllegalArgumentException("Invalid session model");
        }
        Snapshot updated = new Snapshot(snapshot.id(), snapshot.workspace(), model, snapshot.title(),
                instructions, snapshot.createdAt(), clock.millis(), input, snapshot.rules());
        return updatePreservingState(updated, 0, 0);
    }

    /** Loads the exact-match permission rules bound to one saved session. */
    public SessionRules loadRules(String sessionId) throws IOException {
        return load(sessionId).rules();
    }

    /** Atomically replaces embedded rules and returns the authoritative published snapshot. */
    Snapshot updateRules(Snapshot active, SessionRules rules, long expectedGeneration) throws IOException {
        if (expectedGeneration == Long.MAX_VALUE) {
            throw new IOException("Permission rule mutation generation exhausted");
        }
        if (rules.generation() != expectedGeneration + 1) {
            throw new IOException("Invalid permission rule generation");
        }
        return lockedSupply(() -> {
            Snapshot current = load(active.id());
            if (current.rules().generation() != expectedGeneration) {
                throw new IOException("Stale permission rule generation for session " + active.id());
            }
            Snapshot updated = current.withRules(rules);
            writeSnapshotUnlocked(updated, sessionDirectory(updated.id()), false);
            return updated;
        });
    }

    public void delete(String id) throws IOException {
        locked(() -> {
            Path directory = sessionDirectory(id);
            rejectSymlink(directory);
            deleteSessionDirectory(directory);
        });
    }

    private void deleteSessionDirectory(Path directory) throws IOException {
        Path file = directory.resolve("session.json");
        SessionImages.deleteSidecars(directory);
        ToolResultStore.deleteSidecars(directory);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file);
        Files.delete(directory);
    }

    private ObjectNode encode(Snapshot snapshot, ArrayNode input, ArtifactManifest artifacts) {
        ObjectNode node = json.createObjectNode();
        node.put("schema_version", SCHEMA_VERSION);
        node.put("id", snapshot.id());
        node.put("workspace", snapshot.workspace());
        node.put("model", snapshot.model());
        node.put("title", snapshot.title());
        node.put("instructions", snapshot.instructions());
        node.put("created_at", snapshot.createdAt());
        node.put("updated_at", snapshot.updatedAt());
        node.set("input", input);
        node.set("permission_state", snapshot.rules().encode(json));
        node.set("usage_state", snapshot.usage().encode(json));
        ObjectNode encodedArtifacts = node.putObject("artifacts");
        ArrayNode images = encodedArtifacts.putArray("images");
        artifacts.images().forEach(images::add);
        ArrayNode results = encodedArtifacts.putArray("tool_results");
        artifacts.toolResults().forEach(results::add);
        return node;
    }

    private Decoded decode(String expectedId, JsonNode node) throws IOException {
        if (!node.isObject()) {
            throw new IOException("Unsupported or corrupt session snapshot: " + expectedId);
        }
        int schemaVersion = node.path("schema_version").asInt(-1);
        if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != ARTIFACT_SCHEMA_VERSION
                && schemaVersion != SCHEMA_VERSION) {
            throw new IOException("Unsupported or corrupt session snapshot: " + expectedId);
        }
        String id = requiredText(node, "id");
        if (!id.equals(expectedId) || !safeId(id)) throw new IOException("Session identity mismatch: " + expectedId);
        String workspace = requiredText(node, "workspace");
        String model = requiredText(node, "model");
        String title = requiredText(node, "title");
        String instructions = requiredText(node, "instructions");
        long created = requiredLong(node, "created_at");
        long updated = requiredLong(node, "updated_at");
        if (created < 0 || updated < created || !node.path("input").isArray()) {
            throw new IOException("Invalid session timeline or input: " + expectedId);
        }
        // Snapshots written before usage tracking have no usage_state field;
        // they load as zeroed state and gain explicit state when next saved.
        // An explicit JSON null is a present-but-invalid field and fails closed
        // like any other corrupt usage state.
        JsonNode usageNode = node.get("usage_state");
        SessionUsage usage = usageNode == null
                ? SessionUsage.zeroed() : SessionUsage.decode(usageNode);
        Snapshot snapshot = new Snapshot(id, workspace, model, title, instructions, created, updated,
                (ArrayNode) node.path("input"), schemaVersion == SCHEMA_VERSION
                        ? SessionRules.decode(node.get("permission_state")) : new SessionRules(), usage);
        ArtifactManifest artifacts = schemaVersion >= ARTIFACT_SCHEMA_VERSION
                ? ArtifactManifest.decode(node.path("artifacts")) : null;
        return new Decoded(snapshot, artifacts);
    }

    private void validate(Snapshot snapshot) throws IOException {
        if (!safeId(snapshot.id())) throw new IllegalArgumentException("Unsafe session id: " + snapshot.id());
        if (snapshot.workspace().isBlank() || snapshot.model().isBlank() || snapshot.instructions() == null) {
            throw new IllegalArgumentException("Session identity fields must not be blank");
        }
        if (snapshot.updatedAt() < snapshot.createdAt()) throw new IllegalArgumentException("Invalid session timeline");
    }

    private void validateEncoded(Snapshot snapshot, ObjectNode encoded) throws IOException {
        if (json.writeValueAsBytes(encoded).length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Session snapshot exceeds 8 MiB: " + snapshot.id());
        }
    }

    private Snapshot updatePreservingState(Snapshot candidate, long inputTokenDelta,
                                            long outputTokenDelta) throws IOException {
        validate(candidate);
        return lockedSupply(() -> {
            Snapshot current = load(candidate.id());
            SessionUsage usage = current.usage().added(inputTokenDelta, outputTokenDelta);
            Snapshot updated = candidate.withRules(current.rules()).withUsage(usage);
            writeSnapshotUnlocked(updated, sessionDirectory(updated.id()), false);
            writeLatestUnlocked(updated.workspace(), updated.id());
            return updated;
        });
    }

    private void writeSnapshotUnlocked(Snapshot snapshot, Path directory, boolean requireAtomic) throws IOException {
        ArrayNode persisted = SessionImages.externalize(directory, snapshot.input());
        ArtifactManifest artifacts = ArtifactManifest.from(persisted);
        SessionImages.verifySidecars(directory, artifacts.images());
        ToolResultStore.verifySidecars(directory, artifacts.toolResults());
        ObjectNode encoded = encode(snapshot, persisted, artifacts);
        validateEncoded(snapshot, encoded);
        atomicWrite(directory.resolve("session.json"), json.writeValueAsString(encoded), requireAtomic);
    }

    private Path sessionFile(String id) {
        return sessionDirectory(id).resolve("session.json");
    }

    private Path sessionDirectory(String id) {
        if (!safeId(id)) throw new IllegalArgumentException("Unsafe session id: " + id);
        return sessions.resolve(id);
    }

    private void writeLatestUnlocked(String workspace, String id) throws IOException {
        atomicWrite(latest.resolve(workspaceKey(workspace) + ".txt"), id + "\n");
    }

    private void locked(IoAction action) throws IOException {
        lockedSupply(() -> {
            action.run();
            return null;
        });
    }

    private <T> T lockedSupply(IoSupply<T> action) throws IOException {
        synchronized (JVM_MUTATION_LOCK) {
            createManagedDirectory(root);
            Path lockPath = root.resolve("sessions.lock");
            rejectSymlink(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var ignored = channel.lock()) {
                return action.run();
            }
        }
    }

    @FunctionalInterface
    private interface IoAction { void run() throws IOException; }

    @FunctionalInterface
    private interface IoSupply<T> { T run() throws IOException; }

    @FunctionalInterface
    interface RecoveryFailpoint {
        RecoveryFailpoint NONE = () -> { };
        void beforePromotion() throws IOException;
    }

    private void atomicWrite(Path target, String content) throws IOException {
        atomicWrite(target, content, false);
    }

    private void atomicWrite(Path target, String content, boolean requireAtomic) throws IOException {
        createManagedDirectory(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) rejectSymlink(target);
        Path temporary = Files.createTempFile(target.getParent(), ".session-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (requireAtomic) throw new IOException("Atomic session snapshot persistence is unsupported", unsupported);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void createManagedDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Managed path is not a directory: " + directory);
            }
            return;
        }
        Files.createDirectories(directory);
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Managed session path is a symlink: " + path);
    }

    private static String canonicalWorkspace(Path workspace) throws IOException {
        if (!Files.isDirectory(workspace)) throw new IOException("Workspace is not a directory: " + workspace);
        return workspace.toRealPath().toString();
    }

    private static String workspaceKey(String workspace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(workspace.getBytes(StandardCharsets.UTF_8));
            return Hex.encode(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String newId(long millis) {
        return ID_TIME.format(Instant.ofEpochMilli(millis)) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean safeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IOException("Invalid session field: " + field);
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) throw new IOException("Invalid session field: " + field);
        return value.asLong();
    }

    public static final class Snapshot {
        private final String id;
        private final String workspace;
        private final String model;
        private final String title;
        private final String instructions;
        private final long createdAt;
        private final long updatedAt;
        private final ArrayNode input;
        private final SessionRules rules;
        private final SessionUsage usage;

        public Snapshot(String id, String workspace, String model, String title, String instructions,
                        long createdAt, long updatedAt, ArrayNode input) {
            this(id, workspace, model, title, instructions, createdAt, updatedAt, input,
                    new SessionRules(), SessionUsage.zeroed());
        }

        Snapshot(String id, String workspace, String model, String title, String instructions,
                 long createdAt, long updatedAt, ArrayNode input, SessionRules rules) {
            this(id, workspace, model, title, instructions, createdAt, updatedAt, input, rules,
                    SessionUsage.zeroed());
        }

        Snapshot(String id, String workspace, String model, String title, String instructions,
                 long createdAt, long updatedAt, ArrayNode input, SessionRules rules, SessionUsage usage) {
            this.id = id;
            this.workspace = workspace;
            this.model = model;
            this.title = title == null ? "" : title;
            this.instructions = instructions;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.input = input == null ? new ObjectMapper().createArrayNode() : input.deepCopy();
            this.rules = rules == null ? new SessionRules() : rules.copy();
            this.usage = usage == null ? SessionUsage.zeroed() : usage;
        }

        public String id() { return id; }
        public String workspace() { return workspace; }
        public String model() { return model; }
        public String title() { return title; }
        public String instructions() { return instructions; }
        public long createdAt() { return createdAt; }
        public long updatedAt() { return updatedAt; }
        public ArrayNode input() { return input.deepCopy(); }
        SessionRules rules() { return rules.copy(); }
        SessionUsage usage() { return usage; }

        Snapshot withRules(SessionRules replacement) {
            return new Snapshot(id, workspace, model, title, instructions, createdAt, updatedAt,
                    input, replacement, usage);
        }

        Snapshot withUsage(SessionUsage replacement) {
            return new Snapshot(id, workspace, model, title, instructions, createdAt, updatedAt,
                    input, rules, replacement);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Snapshot)) return false;
            Snapshot that = (Snapshot) other;
            return createdAt == that.createdAt && updatedAt == that.updatedAt
                    && Objects.equals(id, that.id) && Objects.equals(workspace, that.workspace)
                    && Objects.equals(model, that.model) && Objects.equals(title, that.title)
                    && Objects.equals(instructions, that.instructions) && Objects.equals(input, that.input)
                    && Objects.equals(usage, that.usage)
                    && rules.generation() == that.rules.generation() && rules.nextId() == that.rules.nextId()
                    && rules.all().equals(that.rules.all());
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(id);
            result = 31 * result + Objects.hashCode(workspace);
            result = 31 * result + Objects.hashCode(model);
            result = 31 * result + Objects.hashCode(title);
            result = 31 * result + Objects.hashCode(instructions);
            result = 31 * result + Long.hashCode(createdAt);
            result = 31 * result + Long.hashCode(updatedAt);
            result = 31 * result + Objects.hashCode(input);
            result = 31 * result + Objects.hashCode(usage);
            result = 31 * result + Long.hashCode(rules.generation());
            result = 31 * result + Long.hashCode(rules.nextId());
            return 31 * result + rules.all().hashCode();
        }

        @Override
        public String toString() {
            return "Snapshot[id=" + id + ", workspace=" + workspace + ", model=" + model
                    + ", title=" + title + ", instructions=" + instructions + ", createdAt=" + createdAt
                    + ", updatedAt=" + updatedAt + ", input=" + input + ", usage=" + usage + "]";
        }
    }

    private static final class Decoded {
        private final Snapshot snapshot;
        private final ArtifactManifest artifacts;

        private Decoded(Snapshot snapshot, ArtifactManifest artifacts) {
            this.snapshot = snapshot;
            this.artifacts = artifacts;
        }

        Snapshot snapshot() { return snapshot; }
        ArtifactManifest artifacts() { return artifacts; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Decoded)) return false;
            Decoded that = (Decoded) other;
            return Objects.equals(snapshot, that.snapshot) && Objects.equals(artifacts, that.artifacts);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(snapshot) + Objects.hashCode(artifacts);
        }

        @Override
        public String toString() {
            return "Decoded[snapshot=" + snapshot + ", artifacts=" + artifacts + "]";
        }
    }

    private static final class ArtifactManifest {
        private final List<String> images;
        private final List<String> toolResults;

        private ArtifactManifest(List<String> images, List<String> toolResults) {
            this.images = List.copyOf(images);
            this.toolResults = List.copyOf(toolResults);
        }

        List<String> images() { return images; }
        List<String> toolResults() { return toolResults; }

        static ArtifactManifest from(JsonNode input) throws IOException {
            return new ArtifactManifest(SessionImages.referencedFiles(input),
                    ToolResultStore.referencedHandles(input));
        }

        static ArtifactManifest decode(JsonNode node) throws IOException {
            if (!node.isObject()) throw new IOException("Invalid session artifact manifest");
            return new ArtifactManifest(readList(node, "images"), readList(node, "tool_results"));
        }

        void validateAgainst(JsonNode input) throws IOException {
            ArtifactManifest actual = from(input);
            if (!equals(actual)) throw new IOException("Session artifact manifest mismatch");
        }

        private static List<String> readList(JsonNode node, String field) throws IOException {
            JsonNode values = node.get(field);
            if (values == null || !values.isArray() || values.size() > MAX_ARTIFACT_REFERENCES) {
                throw new IOException("Invalid session artifact manifest field: " + field);
            }
            List<String> result = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().length() > 160) {
                    throw new IOException("Invalid session artifact manifest field: " + field);
                }
                result.add(value.asText());
            }
            List<String> canonical = new LinkedHashSet<>(result).stream().sorted()
                    .collect(Collectors.toList());
            if (!result.equals(canonical)) throw new IOException("Non-canonical session artifact manifest");
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ArtifactManifest)) return false;
            ArtifactManifest that = (ArtifactManifest) other;
            return Objects.equals(images, that.images) && Objects.equals(toolResults, that.toolResults);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(images) + Objects.hashCode(toolResults);
        }

        @Override
        public String toString() {
            return "ArtifactManifest[images=" + images + ", toolResults=" + toolResults + "]";
        }
    }
}
