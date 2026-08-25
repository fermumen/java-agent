package dev.fxjava;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Keeps durable session mechanics separate from the response/tool loop. */
final class SessionRuntime {
    private Agent agent;
    private final SessionStore store;
    private SessionStore.Snapshot snapshot;
    private volatile SessionRules rules;

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
        try {
            String answer = agent.prompt(input, textDelta, turnListener);
            persist();
            return answer;
        } catch (IOException | InterruptedException primary) {
            try {
                persist();
            } catch (IOException persistenceFailure) {
                primary.addSuppressed(persistenceFailure);
            }
            throw primary;
        }
    }

    void setToolProgress(PrintStream progress) {
        agent.setProgress(progress);
    }

    void clear(String instructions) throws IOException {
        agent.clearConversation(instructions);
        persist();
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

    synchronized void newSession(Path workspace, String model, String instructions) throws IOException {
        requirePersistence();
        SessionStore.Snapshot created = store.create(workspace, model, instructions);
        SessionRules createdRules = created.rules();
        agent.clearConversation(instructions);
        snapshot = created;
        rules = createdRules;
        agent.setToolResultSession(created.id());
    }

    synchronized void resume(String id, Path workspace) throws IOException {
        requirePersistence();
        SessionStore.Snapshot loaded = id.equals("last") ? store.latest(workspace) : store.load(id);
        requireWorkspace(loaded, workspace);
        SessionRules loadedRules = loaded.rules();
        agent.restoreConversation(loaded.input(), loaded.instructions());
        snapshot = loaded;
        rules = loadedRules;
        agent.setToolResultSession(snapshot.id());
    }

    synchronized void recover(String id, Path workspace) throws IOException {
        requirePersistence();
        SessionStore.Snapshot source = store.load(id);
        requireWorkspace(source, workspace);
        SessionStore.Snapshot recovered = store.recover(id);
        SessionRules recoveredRules = recovered.rules();
        agent.restoreConversation(recovered.input(), recovered.instructions());
        snapshot = recovered;
        rules = recoveredRules;
        agent.setToolResultSession(snapshot.id());
    }

    void rename(String title) throws IOException {
        requirePersistence();
        snapshot = store.rename(snapshot, title);
    }

    void reconfigure(Agent replacement, String model, String instructions) throws IOException {
        replacement.restoreConversation(agent.snapshotInput(), instructions);
        SessionStore.Snapshot updated = store == null ? null
                : store.reconfigure(snapshot, model, replacement.snapshotInput(), instructions);
        agent = replacement;
        if (store != null) snapshot = updated;
        agent.setToolResultSession(snapshot == null ? null : snapshot.id());
    }

    private static void requireWorkspace(SessionStore.Snapshot candidate, Path workspace) throws IOException {
        String canonical = workspace.toRealPath().toString();
        if (!candidate.workspace().equals(canonical)) {
            throw new IOException("Session " + candidate.id() + " belongs to workspace "
                    + candidate.workspace() + ", not " + canonical);
        }
    }

    private void persist() throws IOException {
        if (store != null) {
            snapshot = store.update(snapshot, agent.snapshotInput(), agent.instructions());
            rules = snapshot.rules();
        }
    }

    private void requirePersistence() {
        if (store == null) throw new IllegalStateException("Session persistence is disabled");
    }
}
