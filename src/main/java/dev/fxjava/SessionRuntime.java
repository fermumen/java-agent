package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Keeps durable session mechanics separate from the response/tool loop. */
final class SessionRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Agent agent;
    private final SessionStore store;
    private SessionStore.Snapshot snapshot;
    private volatile SessionRules rules;
    private SessionUsage usage = SessionUsage.zeroed();
    private final List<ImageAttachment> pendingImages = new ArrayList<>();

    private SessionRuntime(Agent agent, SessionStore store, SessionStore.Snapshot snapshot) {
        this.agent = agent;
        this.store = store;
        this.snapshot = snapshot;
        agent.setToolResultSession(snapshot == null ? null : snapshot.id());
    }

    private SessionRuntime(Agent agent, SessionStore store, SessionStore.Snapshot snapshot,
                           SessionRules rules) {
        this(agent, store, snapshot);
        this.rules = rules;
        this.usage = snapshot == null ? SessionUsage.zeroed() : snapshot.usage();
    }

    static SessionRuntime start(Agent agent, SessionStore store, Path workspace, String model,
                                String instructions, String resume) throws IOException {
        if (store == null) return new SessionRuntime(agent, null, null);
        SessionStore.Snapshot snapshot;
        if (resume == null) {
            snapshot = store.create(workspace, model, instructions);
        } else {
            snapshot = resume.equals("last") ? store.latest(workspace) : store.load(resume);
            String canonicalWorkspace = workspace.toRealPath().toString();
            if (!snapshot.workspace().equals(canonicalWorkspace)) {
                throw new IOException("Session " + snapshot.id() + " belongs to workspace "
                        + snapshot.workspace() + ", not " + canonicalWorkspace);
            }
        }
        SessionRules rules = snapshot.rules();
        if (resume != null) agent.restoreConversation(snapshot.input(), snapshot.instructions());
        return new SessionRuntime(agent, store, snapshot, rules);
    }

    String prompt(String input) throws IOException, InterruptedException {
        return prompt(input, ignored -> { });
    }

    String prompt(String input, Consumer<String> textDelta) throws IOException, InterruptedException {
        return prompt(input, textDelta, Agent.TurnListener.NONE);
    }

    String prompt(String input, Consumer<String> textDelta, Agent.TurnListener turnListener)
            throws IOException, InterruptedException {
        java.util.concurrent.atomic.AtomicLong inputTokens = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong outputTokens = new java.util.concurrent.atomic.AtomicLong();
        Agent.TurnListener usageListener = wrapWithUsage(turnListener, inputTokens, outputTokens);
        // Submitting consumes staged attachments exactly once, like fx clearing
        // the draft's placeholders: a failed turn does not re-attach them.
        ObjectNode multimodal = buildMultimodalMessage(drainPendingImages(), input);
        try {
            String answer = multimodal == null
                    ? agent.prompt(input, textDelta, usageListener)
                    : agent.promptWithUserMessage(multimodal, textDelta, usageListener);
            persist(inputTokens.getAndSet(0), outputTokens.getAndSet(0));
            return answer;
        } catch (IOException | InterruptedException primary) {
            try {
                persist(inputTokens.getAndSet(0), outputTokens.getAndSet(0));
            } catch (IOException persistenceFailure) {
                primary.addSuppressed(persistenceFailure);
            }
            throw primary;
        }
    }

    /**
     * One user message carrying input_text plus one input_image part per
     * staged attachment; null when no images are staged so plain turns keep
     * their exact historical string-content shape.
     */
    private static ObjectNode buildMultimodalMessage(List<ImageAttachment> images, String input) {
        if (images.isEmpty()) return null;
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("type", "input_text").put("text", input == null ? "" : input);
        for (ImageAttachment image : images) content.add(image.inputPart(JSON, "auto"));
        return message;
    }

    /** Snapshot of images staged via /image that ride with the next submitted message. */
    synchronized List<ImageAttachment> pendingImages() {
        return List.copyOf(pendingImages);
    }

    /** Stages one attachment for the next submitted message, bounded per prompt. */
    synchronized void stagePendingImage(ImageAttachment image) throws IOException {
        if (pendingImages.size() >= ImageAttachment.MAX_IMAGES_PER_PROMPT) {
            throw new IOException("at most " + ImageAttachment.MAX_IMAGES_PER_PROMPT
                    + " images can be attached per message");
        }
        pendingImages.add(image);
    }

    /** Discards every staged attachment; true when anything was pending. */
    synchronized boolean clearPendingImages() {
        boolean hadPending = !pendingImages.isEmpty();
        pendingImages.clear();
        return hadPending;
    }

    private synchronized List<ImageAttachment> drainPendingImages() {
        if (pendingImages.isEmpty()) return List.of();
        List<ImageAttachment> drained = new ArrayList<>(pendingImages);
        pendingImages.clear();
        return drained;
    }

    /**
     * Forwards tool events untouched and captures per-turn usage deltas so
     * they fold into the same locked snapshot write as the conversation.
     */
    private static Agent.TurnListener wrapWithUsage(Agent.TurnListener listener,
                                                    java.util.concurrent.atomic.AtomicLong inputTokens,
                                                    java.util.concurrent.atomic.AtomicLong outputTokens) {
        return new Agent.TurnListener() {
            @Override public void onToolStart(String name, String preview) {
                listener.onToolStart(name, preview);
            }

            @Override public void onToolEnd(String name, boolean error) {
                listener.onToolEnd(name, error);
            }

            @Override public void onUsage(long input, long output) {
                inputTokens.addAndGet(input);
                outputTokens.addAndGet(output);
                listener.onUsage(input, output);
            }
        };
    }

    void setToolProgress(PrintStream progress) {
        agent.setProgress(progress);
    }

    void clear(String instructions) throws IOException {
        agent.clearConversation(instructions);
        synchronized (this) { pendingImages.clear(); }
        persist(0, 0);
    }

    List<Agent.ToolCallRecord> lastToolCalls() {
        return agent.lastToolCalls();
    }

    String id() {
        return snapshot == null ? null : snapshot.id();
    }

    String model() {
        return snapshot == null ? null : snapshot.model();
    }

    boolean persistent() {
        return store != null;
    }

    /** Exact-match permission rules bound to the active saved session; null without persistence. */
    SessionRules rules() {
        return rules;
    }

    /** Cumulative token totals for the active session, persisted when saving is enabled. */
    SessionUsage usage() {
        return usage;
    }

    synchronized String rememberRule(SessionRules.Kind kind, String tool, String arguments) throws IOException {
        requirePersistence();
        SessionRules candidate = rules.copy();
        long expectedGeneration = candidate.generation();
        String id = candidate.remember(kind, tool, arguments);
        if (id == null) return null;
        snapshot = store.updateRules(snapshot, candidate, expectedGeneration);
        rules = snapshot.rules();
        return id;
    }

    synchronized boolean revokeRule(String id) throws IOException {
        requirePersistence();
        SessionRules candidate = rules.copy();
        long expectedGeneration = candidate.generation();
        if (!candidate.revoke(id)) return false;
        snapshot = store.updateRules(snapshot, candidate, expectedGeneration);
        rules = snapshot.rules();
        return true;
    }

    List<SessionStore.Snapshot> sessions(Path workspace, int limit) throws IOException {
        return store == null ? List.of() : store.list(workspace, limit);
    }

    /**
     * Saved sessions across every workspace: a recency-bounded slice for
     * all-time aggregations plus the total found before the cap was applied.
     */
    AllSessions allTimeSessions(int limit) throws IOException {
        if (store == null) return new AllSessions(List.of(), 0);
        SessionStore.BoundedListing listing = store.listBounded(null, limit);
        return new AllSessions(listing.sessions(), listing.totalFound());
    }

    /** Bounded recency slice plus how many saved sessions existed before capping. */
    static final class AllSessions {
        private final List<SessionStore.Snapshot> sessions;
        private final int totalFound;

        AllSessions(List<SessionStore.Snapshot> sessions, int totalFound) {
            this.sessions = sessions;
            this.totalFound = totalFound;
        }

        List<SessionStore.Snapshot> sessions() { return sessions; }
        int totalFound() { return totalFound; }
    }

    /** Deep copy of the live conversation items, safe for inspection and rebuilds. */
    ArrayNode conversation() {
        return agent.snapshotInput();
    }

    /**
     * One extra non-polluting model completion used by /compact: no tools,
     * nothing appended to the conversation, no durable write. The returned
     * result carries the round-trip's own token usage so callers can meter it.
     */
    Agent.SummarizeResult summarizeWithUsage(String content) throws IOException, InterruptedException {
        return agent.summarizeWithUsage(content, ConversationCompactor.SUMMARIZER_INSTRUCTIONS);
    }

    /** Same seam as {@link #summarizeWithUsage}, discarding the usage figures. */
    String summarize(String content) throws IOException, InterruptedException {
        return summarizeWithUsage(content).summary;
    }

    /**
     * Persists a rebuilt conversation (e.g. post-compaction) through the store
     * first — the on-disk snapshot is authoritative — and only then swaps it
     * into the live agent, folding any usage deltas into the same locked
     * write. If the persist fails the live conversation is never touched, so
     * callers can report failure truthfully.
     */
    synchronized void applyCompaction(ArrayNode rebuiltInput,
                                      long inputTokenDelta, long outputTokenDelta) throws IOException {
        persistWith(rebuiltInput, inputTokenDelta, outputTokenDelta);
        agent.restoreConversation(rebuiltInput, agent.instructions());
    }

    synchronized void newSession(Path workspace, String model, String instructions) throws IOException {
        requirePersistence();
        SessionStore.Snapshot created = store.create(workspace, model, instructions);
        SessionRules createdRules = created.rules();
        agent.clearConversation(instructions);
        pendingImages.clear();
        snapshot = created;
        rules = createdRules;
        usage = created.usage();
        agent.setToolResultSession(created.id());
    }

    synchronized void resume(String id, Path workspace) throws IOException {
        requirePersistence();
        SessionStore.Snapshot loaded = id.equals("last") ? store.latest(workspace) : store.load(id);
        requireWorkspace(loaded, workspace);
        SessionRules loadedRules = loaded.rules();
        SessionUsage loadedUsage = loaded.usage();
        agent.restoreConversation(loaded.input(), loaded.instructions());
        pendingImages.clear();
        snapshot = loaded;
        rules = loadedRules;
        usage = loadedUsage;
        agent.setToolResultSession(snapshot.id());
    }

    synchronized void recover(String id, Path workspace) throws IOException {
        requirePersistence();
        SessionStore.Snapshot source = store.load(id);
        requireWorkspace(source, workspace);
        SessionStore.Snapshot recovered = store.recover(id);
        SessionRules recoveredRules = recovered.rules();
        SessionUsage recoveredUsage = recovered.usage();
        agent.restoreConversation(recovered.input(), recovered.instructions());
        pendingImages.clear();
        snapshot = recovered;
        rules = recoveredRules;
        usage = recoveredUsage;
        agent.setToolResultSession(snapshot.id());
    }

    void rename(String title) throws IOException {
        requirePersistence();
        snapshot = store.rename(snapshot, title);
        // The store re-reads authoritative state under the lock; adopt whatever
        // it returned so local rules/usage never go stale across the rename.
        rules = snapshot.rules();
        usage = snapshot.usage();
    }

    void reconfigure(Agent replacement, String model, String instructions) throws IOException {
        replacement.restoreConversation(agent.snapshotInput(), instructions);
        if (store != null) {
            snapshot = store.reconfigure(snapshot, model, replacement.snapshotInput(), instructions);
            rules = snapshot.rules();
            usage = snapshot.usage();
        }
        agent = replacement;
        agent.setToolResultSession(snapshot == null ? null : snapshot.id());
    }

    private static void requireWorkspace(SessionStore.Snapshot candidate, Path workspace) throws IOException {
        String canonical = workspace.toRealPath().toString();
        if (!candidate.workspace().equals(canonical)) {
            throw new IOException("Session " + candidate.id() + " belongs to workspace "
                    + candidate.workspace() + ", not " + canonical);
        }
    }

    private void persist(long inputTokenDelta, long outputTokenDelta) throws IOException {
        persistWith(agent.snapshotInput(), inputTokenDelta, outputTokenDelta);
    }

    private void persistWith(ArrayNode input, long inputTokenDelta, long outputTokenDelta) throws IOException {
        if (store != null) {
            snapshot = store.update(snapshot, input, agent.instructions(),
                    inputTokenDelta, outputTokenDelta);
            rules = snapshot.rules();
            usage = snapshot.usage();
            return;
        }
        if (inputTokenDelta != 0 || outputTokenDelta != 0) {
            usage = usage.added(inputTokenDelta, outputTokenDelta);
        }
    }

    private void requirePersistence() {
        if (store == null) throw new IllegalStateException("Session persistence is disabled");
    }
}
