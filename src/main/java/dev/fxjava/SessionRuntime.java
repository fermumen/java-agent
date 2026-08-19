package dev.fxjava;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Keeps durable session mechanics separate from the response/tool loop. */
final class SessionRuntime {
    private Agent agent;
    private final SessionStore store;
    private SessionStore.Snapshot snapshot;

    private SessionRuntime(Agent agent, SessionStore store, SessionStore.Snapshot snapshot) {
        this.agent = agent;
        this.store = store;
        this.snapshot = snapshot;
        agent.setToolResultSession(snapshot == null ? null : snapshot.id());
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
            agent.restoreConversation(snapshot.input(), snapshot.instructions());
        }
        return new SessionRuntime(agent, store, snapshot);
    }

    String prompt(String input) throws IOException, InterruptedException {
        return prompt(input, ignored -> { });
    }

    String prompt(String input, Consumer<String> textDelta) throws IOException, InterruptedException {
        try {
            String answer = agent.prompt(input, textDelta);
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

    List<SessionStore.Snapshot> sessions(Path workspace, int limit) throws IOException {
        return store == null ? List.of() : store.list(workspace, limit);
    }

    void newSession(Path workspace, String model, String instructions) throws IOException {
        requirePersistence();
        agent.clearConversation(instructions);
        snapshot = store.create(workspace, model, instructions);
        agent.setToolResultSession(snapshot.id());
    }

    void resume(String id, Path workspace) throws IOException {
        requirePersistence();
        SessionStore.Snapshot loaded = id.equals("last") ? store.latest(workspace) : store.load(id);
        requireWorkspace(loaded, workspace);
        agent.restoreConversation(loaded.input(), loaded.instructions());
        snapshot = loaded;
        agent.setToolResultSession(snapshot.id());
    }

    void recover(String id, Path workspace) throws IOException {
        requirePersistence();
        SessionStore.Snapshot recovered = store.recover(id);
        requireWorkspace(recovered, workspace);
        agent.restoreConversation(recovered.input(), recovered.instructions());
        snapshot = recovered;
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
        if (store != null) snapshot = store.update(snapshot, agent.snapshotInput(), agent.instructions());
    }

    private void requirePersistence() {
        if (store == null) throw new IllegalStateException("Session persistence is disabled");
    }
}
