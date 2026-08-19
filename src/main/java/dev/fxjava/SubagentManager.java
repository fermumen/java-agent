package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Bounded asynchronous child-session manager behind the fx-shaped subagent tool. */
final class SubagentManager implements AutoCloseable {
    private static final int MAX_CHILDREN = 32;
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_EVENTS = 256;
    private static final int MAX_OPERATIONS = 256;
    private static final Set<String> SETTLED = Set.of("idle", "interrupted", "completed", "failed", "cancelled", "archived");
    private final ObjectMapper json;
    private final ChildFactory childFactory;
    private final PermissionMode parentPermission;
    private final ExecutorService executor;
    private final SubagentStateStore stateStore;
    private final Map<String, Child> children = new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationReplay> operations = new LinkedHashMap<>();

    SubagentManager(ObjectMapper json, ChildFactory childFactory, PermissionMode parentPermission) {
        this(json, childFactory, parentPermission, null);
    }

    SubagentManager(ObjectMapper json, ChildFactory childFactory, PermissionMode parentPermission, Path stateRoot) {
        this.json = json;
        this.childFactory = childFactory;
        this.parentPermission = parentPermission;
        this.stateStore = new SubagentStateStore(json, stateRoot);
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "java-agent-subagent");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(4, threads);
    }

    synchronized void restore() throws Exception {
        for (SubagentStateStore.Operation saved : stateStore.loadOperations()) {
            operations.put(saved.operationId(), new OperationReplay(
                    saved.requestFingerprint(), saved.receipt()));
        }
        for (ObjectNode saved : stateStore.load()) {
            if (children.size() >= MAX_CHILDREN) break;
            String id = saved.path("id").asText();
            try { SubagentCommand.validateId(id); } catch (Exception invalid) { continue; }
            String name = saved.path("name").asText();
            String mode = saved.path("mode").asText();
            if (name.isBlank() || !Set.of("one_off", "persistent").contains(mode)) continue;
            JsonNode config = saved.path("configuration");
            PermissionMode permission;
            try {
                permission = clamp(PermissionMode.parse(config.path("permission_mode").asText()), parentPermission);
            } catch (Exception invalid) {
                continue;
            }
            ChildConfiguration configuration = new ChildConfiguration(id, name,
                    config.path("model").isTextual() ? config.path("model").asText() : null,
                    config.path("effort").isTextual() ? config.path("effort").asText() : null, permission);
            ChildRunner runner = childFactory.create(configuration);
            Child child = new Child(id, name, mode, configuration, runner);
            child.state = reconcile(saved.path("state").asText("interrupted"));
            child.archivedFrom = saved.path("archived_from").asText("idle");
            child.parentId = saved.path("parent_id").isTextual() ? saved.path("parent_id").asText() : null;
            child.failure = saved.path("failure").isTextual() ? saved.path("failure").asText() : null;
            child.generation = Math.max(1, saved.path("generation").asLong(1));
            child.eventSequence = Math.max(0, saved.path("event_sequence").asLong());
            for (JsonNode message : saved.path("messages")) {
                if (child.messages.size() >= MAX_MESSAGES) break;
                child.messages.add(new Message(message.path("role").asText(), message.path("content").asText(),
                        message.path("created_at_ms").asLong()));
            }
            if (saved.path("current_prompt").isTextual()) child.queue.add(saved.path("current_prompt").asText());
            for (JsonNode queued : saved.path("queue")) {
                if (queued.isTextual() && child.queue.size() < MAX_MESSAGES) child.queue.add(queued.asText());
            }
            for (JsonNode activity : saved.path("tool_activity")) {
                if (child.toolActivity.size() < MAX_EVENTS) child.toolActivity.add(activity.deepCopy());
            }
            for (JsonNode event : saved.path("events")) {
                if (child.events.size() < MAX_EVENTS) child.events.add(event.deepCopy());
            }
            if (saved.path("conversation").isObject()) runner.restore((ObjectNode) saved.path("conversation"));
            children.put(id, child);
            persist(child);
        }
    }

    private void persist(Child child) throws Exception {
        if (!stateStore.enabled()) return;
        ObjectNode saved = json.createObjectNode().put("schema_version", 1).put("id", child.id)
                .put("name", child.name).put("mode", child.mode).put("state", child.state)
                .put("archived_from", child.archivedFrom).put("generation", child.generation)
                .put("event_sequence", child.eventSequence);
        if (child.parentId == null) saved.putNull("parent_id"); else saved.put("parent_id", child.parentId);
        if (child.failure == null) saved.putNull("failure"); else saved.put("failure", child.failure);
        saved.set("configuration", configuration(child));
        ArrayNode messages = saved.putArray("messages");
        for (Message message : child.messages) {
            messages.addObject().put("role", message.role).put("content", message.content)
                    .put("created_at_ms", message.createdAtMs);
        }
        if (child.currentPrompt == null) saved.putNull("current_prompt");
        else saved.put("current_prompt", child.currentPrompt);
        ArrayNode queue = saved.putArray("queue");
        child.queue.forEach(queue::add);
        saved.set("tool_activity", child.toolActivity.deepCopy());
        saved.set("events", child.events.deepCopy());
        ObjectNode conversation = child.runner.snapshot();
        if (conversation == null) saved.putNull("conversation"); else saved.set("conversation", conversation);
        stateStore.save(child.id, saved);
    }

    private void persistQuietly(Child child) {
        try {
            persist(child);
        } catch (Exception failure) {
            child.failure = "persistence_failed: " + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
            child.state = "failed";
        }
    }

    private static String reconcile(String state) {
        return Set.of("queued", "running", "awaiting_approval").contains(state) ? "interrupted" : state;
    }

    synchronized String execute(SubagentCommand command, String invocationId) throws Exception {
        String operationId = operationId(invocationId);
        String fingerprint = requestFingerprint(command);
        OperationReplay replay = operations.get(operationId);
        if (replay != null) {
            if (replay.fingerprint().equals(fingerprint)) return replay.receipt();
            return failure(operationId, null, "operation_conflict", false);
        }
        String result;
        try {
            result = switch (command.branch()) {
                case "create" -> create(operationId, command.value());
                case "inspect" -> inspect(operationId, command.value());
                case "message" -> message(operationId, command.value());
                case "relationship" -> relationship(operationId, command.value());
                case "configure" -> configure(operationId, command.value());
                case "lifecycle" -> lifecycle(operationId, command.value());
                default -> failure(operationId, null, "invalid_branch_selection", false);
            };
        } catch (SubagentFailure failure) {
            result = failure(operationId, failure.childId, failure.code, failure.retryable);
        }
        remember(operationId, fingerprint, result);
        return result;
    }

    private void remember(String operationId, String fingerprint, String receipt) throws IOException {
        operations.put(operationId, new OperationReplay(fingerprint, receipt));
        while (operations.size() > MAX_OPERATIONS) {
            operations.remove(operations.keySet().iterator().next());
        }
        List<SubagentStateStore.Operation> saved = new ArrayList<>();
        for (var entry : operations.entrySet()) {
            saved.add(new SubagentStateStore.Operation(entry.getKey(), entry.getValue().fingerprint(),
                    entry.getValue().receipt()));
        }
        stateStore.saveOperations(saved);
    }

    private String requestFingerprint(SubagentCommand command) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(command.branch().getBytes(StandardCharsets.UTF_8));
            hashNode(hash, command.value());
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void hashNode(MessageDigest hash, JsonNode node) throws IOException {
        if (node.isObject()) {
            hash.update((byte) 1);
            java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            for (var entry : fields.entrySet()) {
                hash.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                hash.update((byte) 0);
                hashNode(hash, entry.getValue());
            }
        } else if (node.isArray()) {
            hash.update((byte) 2);
            for (JsonNode child : node) hashNode(hash, child);
        } else {
            hash.update((byte) 3);
            hash.update(json.writeValueAsBytes(node));
        }
    }

    private String create(String operationId, ObjectNode value) throws Exception {
        String id;
        Child child;
        synchronized (children) {
            if (children.size() >= MAX_CHILDREN) throw new SubagentFailure(null, "capacity_exceeded", true);
            id = "child-" + UUID.randomUUID().toString().replace("-", "");
            String requestedPermission = value.path("permission_mode").asText(parentPermission.name().toLowerCase());
            PermissionMode permission = clamp(PermissionMode.parse(requestedPermission), parentPermission);
            ChildConfiguration configuration = new ChildConfiguration(id, value.path("name").asText(),
                    value.path("model").isTextual() ? value.path("model").asText() : null,
                    value.path("effort").isTextual() ? value.path("effort").asText() : null, permission);
            ChildRunner runner = childFactory.create(configuration);
            child = new Child(id, value.path("name").asText(), value.path("mode").asText(),
                    configuration, runner);
            children.put(id, child);
        }
        String prompt = value.path("prompt").isTextual() ? value.path("prompt").asText() : null;
        if (prompt != null) enqueue(child, prompt);
        synchronized (child) { persist(child); }
        return receipt(operationId, child, "created");
    }

    private String inspect(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        JsonNode wait = value.get("wait");
        boolean timedOut = false;
        if (wait != null) {
            long deadline = System.currentTimeMillis() + wait.path("timeout_ms").asLong();
            long after = wait.path("after_generation").asLong(-1);
            synchronized (child) {
                while (!(SETTLED.contains(child.state) && (after < 0 || child.generation > after))) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) { timedOut = true; break; }
                    child.wait(remaining);
                }
            }
        }
        ObjectNode requested = json.createObjectNode();
        requested.put("child_id", child.id);
        String nextCursor = null;
        ArrayNode sections = (ArrayNode) value.path("sections");
        synchronized (child) {
            for (JsonNode sectionNode : sections) {
                switch (sectionNode.asText()) {
                    case "status" -> requested.set("status", status(child));
                    case "messages" -> {
                        MessagePage page = messages(child, value);
                        requested.set("messages", page.values());
                        nextCursor = page.nextCursor();
                    }
                    case "tool_activity" -> requested.set("tool_activity", child.toolActivity.deepCopy());
                    case "events" -> requested.set("events", child.events.deepCopy());
                    case "configuration" -> requested.set("configuration", configuration(child));
                    case "relationship" -> requested.set("relationship", relationship(child));
                    default -> { }
                }
            }
        }
        String status = timedOut ? "wait_timed_out" : child.state;
        return outcome(true, operationId, child.id, status, null, timedOut, requested, nextCursor);
    }

    private String message(String operationId, ObjectNode value) throws Exception {
        if (value.has("milestone")) {
            ObjectNode requested = json.createObjectNode().put("outcome", "milestone_emitted")
                    .put("name", value.path("milestone").path("name").asText());
            return outcome(true, operationId, null, "milestone_emitted", null, false, requested, null);
        }
        JsonNode send = value.path("send");
        Child child = child(send.path("id").asText());
        if (!child.mode.equals("persistent")) throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
        synchronized (child) {
            if (child.state.equals("archived")) throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
        }
        enqueue(child, send.path("content").asText());
        return receipt(operationId, child, "message_queued");
    }

    private String configure(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        synchronized (child) {
            String name = value.path("name").isTextual() ? value.path("name").asText() : child.configuration.name();
            String model = value.path("model").isTextual() ? value.path("model").asText() : child.configuration.model();
            String effort = value.path("effort").isTextual() ? value.path("effort").asText() : child.configuration.effort();
            PermissionMode permission = value.path("permission_mode").isTextual()
                    ? clamp(PermissionMode.parse(value.path("permission_mode").asText()), parentPermission)
                    : child.configuration.permissionMode();
            ChildConfiguration replacement = new ChildConfiguration(child.id, name, model, effort, permission);
            try {
                child.runner.configure(replacement);
            } catch (Exception failure) {
                throw new SubagentFailure(child.id, "configuration_failed", true);
            }
            child.configuration = replacement;
            child.name = name;
            changed(child, "configured");
        }
        return receipt(operationId, child, "configured");
    }

    private String relationship(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        synchronized (child) {
            switch (value.path("action").asText()) {
                case "detach" -> child.parentId = null;
                case "attach" -> child.parentId = "root";
                case "reparent" -> {
                    String parent = value.path("parent_id").asText();
                    child(parent);
                    if (createsCycle(child.id, parent)) throw new SubagentFailure(child.id, "invalid_relationship", false);
                    child.parentId = parent;
                }
                default -> throw new SubagentFailure(child.id, "invalid_relationship", false);
            }
            changed(child, "relationship_changed");
        }
        return receipt(operationId, child, "relationship_changed");
    }

    private String lifecycle(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        String action = value.path("action").asText();
        synchronized (child) {
            switch (action) {
                case "cancel" -> {
                    if (!Set.of("queued", "running", "interrupted").contains(child.state)) {
                        throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    }
                    if (child.future != null) child.future.cancel(true);
                    child.state = child.mode.equals("persistent") ? "idle" : "cancelled";
                    child.currentPrompt = null;
                }
                case "resume" -> {
                    if (!child.state.equals("interrupted")) {
                        throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    }
                    child.state = child.queue.isEmpty() ? "idle" : "queued";
                    if (!child.queue.isEmpty()) submitNext(child);
                }
                case "close" -> {
                    if (child.state.equals("archived")) throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    child.archivedFrom = child.state;
                    if (child.future != null) child.future.cancel(true);
                    child.state = "archived";
                }
                case "reopen" -> {
                    if (!child.state.equals("archived")) throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    child.state = child.queue.isEmpty() ? terminalOrIdle(child.archivedFrom) : "queued";
                    if (!child.queue.isEmpty()) submitNext(child);
                }
                default -> throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
            }
            changed(child, action);
        }
        return receipt(operationId, child, "lifecycle_changed");
    }

    private void enqueue(Child child, String prompt) {
        synchronized (child) {
            if (child.messages.size() >= MAX_MESSAGES) child.messages.remove(0);
            child.messages.add(new Message("user", prompt, System.currentTimeMillis()));
            child.queue.add(prompt);
            if (SETTLED.contains(child.state) && !child.state.equals("archived")) submitNext(child);
        }
    }

    private void submitNext(Child child) {
        if (child.queue.isEmpty()) return;
        String prompt = child.queue.remove();
        child.currentPrompt = prompt;
        child.state = "queued";
        changed(child, "queued");
        child.future = executor.submit(() -> run(child, prompt));
    }

    private void run(Child child, String prompt) {
        synchronized (child) {
            if (child.state.equals("archived")) return;
            child.state = "running";
            changed(child, "running");
        }
        try {
            String answer = child.runner.prompt(prompt);
            synchronized (child) {
                child.messages.add(new Message("assistant", answer, System.currentTimeMillis()));
                child.currentPrompt = null;
                child.toolActivity.removeAll();
                for (Agent.ToolCallRecord activity : child.runner.toolActivity()) {
                    child.toolActivity.addObject().put("name", activity.name()).put("status", activity.status());
                }
                child.failure = null;
                child.state = child.mode.equals("one_off") ? "completed" : "idle";
                changed(child, child.state);
                if (child.mode.equals("persistent") && !child.queue.isEmpty()) submitNext(child);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (child) {
                if (!child.state.equals("archived") && !child.state.equals("cancelled") && !child.state.equals("idle")) {
                    child.state = "interrupted";
                    changed(child, "interrupted");
                }
            }
        } catch (Exception failure) {
            synchronized (child) {
                child.failure = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                child.currentPrompt = null;
                child.state = "failed";
                changed(child, "failed");
            }
        }
    }

    private ObjectNode status(Child child) {
        ObjectNode status = json.createObjectNode().put("state", child.state)
                .put("generation", child.generation).put("event_sequence", child.eventSequence);
        if (child.failure == null) status.putNull("failure_reason"); else status.put("failure_reason", child.failure);
        return status;
    }

    private MessagePage messages(Child child, ObjectNode request) throws SubagentFailure {
        int start = 0;
        if (request.path("cursor").isTextual()) {
            String[] parts = request.path("cursor").asText().split(":", -1);
            try {
                long generation = Long.parseLong(parts[1]);
                start = Integer.parseInt(parts[2]);
                if (generation != child.generation || start > child.messages.size()) {
                    throw new SubagentFailure(child.id, "stale_cursor", false);
                }
            } catch (NumberFormatException invalid) {
                throw new SubagentFailure(child.id, "invalid_cursor", false);
            }
        }
        int limit = request.path("limit").asInt(50);
        ArrayNode result = json.createArrayNode();
        int index = start;
        for (; index < child.messages.size() && result.size() < limit; index++) {
            Message message = child.messages.get(index);
            result.addObject().put("sequence", index + 1).put("role", message.role)
                    .put("content", message.content).put("created_at_ms", message.createdAtMs);
        }
        String next = index < child.messages.size() ? "v1:" + child.generation + ":" + index : null;
        return new MessagePage(result, next);
    }

    private ObjectNode configuration(Child child) {
        ObjectNode result = json.createObjectNode().put("name", child.configuration.name())
                .put("permission_mode", child.configuration.permissionMode().name().toLowerCase());
        if (child.configuration.model() == null) result.putNull("model"); else result.put("model", child.configuration.model());
        if (child.configuration.effort() == null) result.putNull("effort"); else result.put("effort", child.configuration.effort());
        return result;
    }

    private ObjectNode relationship(Child child) {
        ObjectNode result = json.createObjectNode();
        if (child.parentId == null) result.putNull("parent_id"); else result.put("parent_id", child.parentId);
        result.put("attached", child.parentId != null);
        return result;
    }

    private String receipt(String operationId, Child child, String code) throws IOException {
        ObjectNode requested = json.createObjectNode().put("outcome", code)
                .put("generation", child.generation).put("event_sequence", child.eventSequence);
        return outcome(true, operationId, child.id, code, null, false, requested, null);
    }

    private String failure(String operationId, String childId, String code, boolean retryable) throws IOException {
        return outcome(false, operationId, childId, "rejected", code, retryable, null, null);
    }

    private String outcome(boolean ok, String operationId, String childId, String status, String error,
                           boolean retryable, JsonNode requested, String cursor) throws IOException {
        ObjectNode result = json.createObjectNode().put("ok", ok).put("operation_id", operationId);
        if (childId == null) result.putNull("child_id"); else result.put("child_id", childId);
        result.put("status", status);
        if (error == null) result.putNull("error_code"); else result.put("error_code", error);
        result.put("retryable", retryable);
        if (requested == null) result.putNull("requested"); else result.set("requested", requested);
        if (cursor == null) result.putNull("cursor"); else result.put("cursor", cursor);
        return json.writeValueAsString(result);
    }

    private Child child(String id) throws SubagentFailure {
        synchronized (children) {
            Child child = children.get(id);
            if (child == null) throw new SubagentFailure(id, "session_not_found", false);
            return child;
        }
    }

    private boolean createsCycle(String childId, String parentId) {
        String current = parentId;
        for (int depth = 0; depth <= MAX_CHILDREN && current != null; depth++) {
            if (current.equals(childId)) return true;
            Child parent = children.get(current);
            current = parent == null ? null : parent.parentId;
        }
        return false;
    }

    private static PermissionMode clamp(PermissionMode requested, PermissionMode parent) {
        return requested.ordinal() > parent.ordinal() ? parent : requested;
    }

    private static String terminalOrIdle(String archivedFrom) {
        return Set.of("completed", "failed", "cancelled").contains(archivedFrom) ? archivedFrom : "idle";
    }

    private static int parseCursor(String cursor) {
        try {
            int parsed = Integer.parseInt(cursor);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw SubagentCommand.invalid("invalid_cursor");
        }
    }

    private void changed(Child child, String kind) {
        child.generation++;
        child.eventSequence++;
        child.events.addObject().put("sequence", child.eventSequence).put("kind", kind)
                .put("created_at_ms", System.currentTimeMillis());
        if (child.events.size() > MAX_EVENTS) child.events.remove(0);
        persistQuietly(child);
        child.notifyAll();
    }

    private static String operationId(String invocation) {
        if (invocation != null && !invocation.isEmpty() && invocation.length() <= 128
                && invocation.chars().noneMatch(Character::isWhitespace)) return invocation;
        return "call_" + digest(invocation == null ? "" : invocation);
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public void close() {
        synchronized (children) {
            children.values().forEach(child -> { if (child.future != null) child.future.cancel(true); });
        }
        executor.shutdownNow();
    }

    interface ChildRunner {
        String prompt(String prompt) throws Exception;
        default void configure(ChildConfiguration configuration) throws Exception { }
        default ObjectNode snapshot() throws Exception { return null; }
        default List<Agent.ToolCallRecord> toolActivity() { return List.of(); }
        default void restore(ObjectNode snapshot) throws Exception { }
    }
    interface ChildFactory { ChildRunner create(ChildConfiguration configuration) throws Exception; }
    record ChildConfiguration(String id, String name, String model, String effort, PermissionMode permissionMode) { }
    private record Message(String role, String content, long createdAtMs) { }
    private record MessagePage(ArrayNode values, String nextCursor) { }
    private record OperationReplay(String fingerprint, String receipt) { }

    private final class Child {
        final String id;
        final String mode;
        final ChildRunner runner;
        final ArrayDeque<String> queue = new ArrayDeque<>();
        final List<Message> messages = new ArrayList<>();
        final ArrayNode events = json.createArrayNode();
        final ArrayNode toolActivity = json.createArrayNode();
        String name;
        ChildConfiguration configuration;
        String state = "idle";
        String archivedFrom = "idle";
        String parentId = "root";
        String failure;
        String currentPrompt;
        long generation = 1;
        long eventSequence;
        Future<?> future;

        Child(String id, String name, String mode, ChildConfiguration configuration, ChildRunner runner) {
            this.id = id;
            this.name = name;
            this.mode = mode;
            this.configuration = configuration;
            this.runner = runner;
        }
    }

    private static final class SubagentFailure extends Exception {
        final String childId;
        final String code;
        final boolean retryable;
        SubagentFailure(String childId, String code, boolean retryable) {
            this.childId = childId;
            this.code = code;
            this.retryable = retryable;
        }
    }
}
