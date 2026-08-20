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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongSupplier;

/** Bounded asynchronous child-session manager behind the fx-shaped subagent tool. */
final class SubagentManager implements AutoCloseable {
    private static final int MAX_CHILDREN = 32;
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_EVENTS = 256;
    private static final int MAX_OPERATIONS = 256;
    private static final int MAX_PARENT_DELIVERIES = 32;
    private static final int MAX_PARENT_CONTEXT_BYTES = 16 * 1024;
    private static final Set<String> SETTLED = Set.of("idle", "interrupted", "completed", "failed", "cancelled", "archived");
    private final ObjectMapper json;
    private final ChildFactory childFactory;
    private final PermissionMode parentPermission;
    private final ExecutorService executor;
    private final SubagentStateStore stateStore;
    private final LongSupplier clock;
    private final Map<String, Child> children = new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationReplay> operations = new LinkedHashMap<>();
    private final Object operationMutex = new Object();

    SubagentManager(ObjectMapper json, ChildFactory childFactory, PermissionMode parentPermission) {
        this(json, childFactory, parentPermission, null);
    }

    SubagentManager(ObjectMapper json, ChildFactory childFactory, PermissionMode parentPermission, Path stateRoot) {
        this(json, childFactory, parentPermission, stateRoot, System::currentTimeMillis);
    }

    SubagentManager(ObjectMapper json, ChildFactory childFactory, PermissionMode parentPermission, Path stateRoot,
                    LongSupplier clock) {
        this.json = json;
        this.childFactory = childFactory;
        this.parentPermission = parentPermission;
        this.stateStore = new SubagentStateStore(json, stateRoot);
        this.clock = clock;
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
            if (config.path("notifications").isObject()) {
                child.notifications = ((ObjectNode) config.path("notifications")).deepCopy();
            }
            child.state = reconcile(saved.path("state").asText("interrupted"));
            child.archivedFrom = saved.path("archived_from").asText("idle");
            child.parentId = saved.path("parent_id").isTextual() ? saved.path("parent_id").asText() : null;
            child.failure = saved.path("failure").isTextual() ? saved.path("failure").asText() : null;
            child.generation = Math.max(1, saved.path("generation").asLong(1));
            child.eventSequence = Math.max(0, saved.path("event_sequence").asLong());
            child.parentDeliverySequence = Math.max(0, saved.path("parent_delivery_sequence").asLong());
            child.notificationWorkStartedAt = saved.path("notification_work_started_at_ms").asLong(-1);
            child.notificationIntervalTick = Math.max(0, saved.path("notification_interval_tick").asLong());
            child.notificationIntervalStopped = saved.path("notification_interval_stopped").asBoolean();
            child.notificationWorkState = saved.path("notification_work_state").asText("idle");
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
                .put("event_sequence", child.eventSequence)
                .put("parent_delivery_sequence", child.parentDeliverySequence)
                .put("notification_work_started_at_ms", child.notificationWorkStartedAt)
                .put("notification_interval_tick", child.notificationIntervalTick)
                .put("notification_interval_stopped", child.notificationIntervalStopped)
                .put("notification_work_state", child.notificationWorkState);
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
            child.failure = safeFailure("persistence_failed: ", failure);
            child.state = "failed";
        }
    }

    private static String reconcile(String state) {
        return Set.of("queued", "running", "awaiting_approval").contains(state) ? "interrupted" : state;
    }

    synchronized Agent.PreparedParentContext prepareParentContext(String parentId) throws IOException {
        List<Child> ordered = new ArrayList<>(children.values());
        ordered.sort(java.util.Comparator.comparing(child -> child.id));
        ArrayNode deliveries = json.createArrayNode();
        Map<String, Long> through = new LinkedHashMap<>();
        outer:
        for (Child child : ordered) {
            synchronized (child) {
                if (!parentId.equals(child.parentId)) continue;
                materializeInterval(child, parentId, clock.getAsLong());
                for (JsonNode event : child.events) {
                    long sequence = event.path("sequence").asLong();
                    if (sequence <= child.parentDeliverySequence || !isParentDelivery(event, parentId)) continue;
                    ObjectNode delivery = renderParentDelivery(child, event);
                    deliveries.add(delivery);
                    String rendered = renderParentEnvelope(deliveries);
                    if (rendered.getBytes(StandardCharsets.UTF_8).length > MAX_PARENT_CONTEXT_BYTES) {
                        deliveries.remove(deliveries.size() - 1);
                        break outer;
                    }
                    through.put(child.id, sequence);
                    if (deliveries.size() >= MAX_PARENT_DELIVERIES) break outer;
                }
            }
        }
        if (deliveries.isEmpty()) return null;
        List<Agent.ParentDeliveryAck> acknowledgements = new ArrayList<>();
        through.forEach((childId, sequence) ->
                acknowledgements.add(new Agent.ParentDeliveryAck(childId, parentId, sequence)));
        return new Agent.PreparedParentContext(renderParentEnvelope(deliveries), acknowledgements);
    }

    synchronized void acknowledgeParentContext(Agent.PreparedParentContext prepared) throws IOException {
        for (Agent.ParentDeliveryAck acknowledgement : prepared.acknowledgements()) {
            Child child = children.get(acknowledgement.childId());
            if (child == null) continue;
            synchronized (child) {
                if (!acknowledgement.targetParentId().equals(child.parentId)) continue;
                if (acknowledgement.throughSequence() > child.parentDeliverySequence) {
                    child.parentDeliverySequence = Math.min(
                            acknowledgement.throughSequence(), child.eventSequence);
                    try {
                        persist(child);
                    } catch (IOException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IOException("could not persist parent delivery acknowledgement", failure);
                    }
                }
            }
        }
    }

    Agent.ParentContext parentContext(String parentId) {
        return new Agent.ParentContext() {
            public Agent.PreparedParentContext prepare() throws IOException {
                return prepareParentContext(parentId);
            }

            public void acknowledge(Agent.PreparedParentContext prepared) throws IOException {
                acknowledgeParentContext(prepared);
            }
        };
    }

    private boolean isParentDelivery(JsonNode event, String parentId) {
        if (!event.path("target_parent_id").asText("").equals(parentId)) return false;
        return event.path("kind").asText().equals("milestone_emitted")
                || event.path("kind").asText().equals("terminal_notification")
                || event.path("kind").asText().equals("interval_notification");
    }

    private ObjectNode renderParentDelivery(Child child, JsonNode event) {
        ObjectNode delivery = json.createObjectNode().put("sequence", event.path("sequence").asLong())
                .put("id", child.id + ":" + event.path("sequence").asLong())
                .put("source_id", child.id).put("target_id", event.path("target_parent_id").asText())
                .put("timestamp_ms", event.path("created_at_ms").asLong());
        if (event.path("kind").asText().equals("milestone_emitted")) {
            delivery.put("kind", "milestone").put("name", event.path("name").asText());
        } else if (event.path("kind").asText().equals("terminal_notification")) {
            delivery.put("kind", "terminal").put("terminal", event.path("terminal").asText());
            if (event.path("failure_reason").isTextual()) {
                delivery.put("failure_reason", event.path("failure_reason").asText());
            }
        } else {
            delivery.put("kind", "interval").put("state", event.path("state").asText())
                    .put("coalesced_ticks", event.path("coalesced_ticks").asLong());
        }
        return delivery;
    }

    private void materializeInterval(Child child, String parentId, long now) throws IOException {
        if (child.notificationIntervalStopped || child.notificationWorkStartedAt < 0) return;
        long interval = child.notifications.path("report_interval_ms").asLong(0);
        if (interval <= 0) return;
        long duration = child.notifications.path("report_duration_ms").asLong(0);
        boolean durationStopped = duration > 0 && elapsed(now, child.notificationWorkStartedAt) >= duration;
        boolean terminalStopped = terminalStopEnabled(child)
                && Set.of("completed", "failed", "cancelled").contains(child.notificationWorkState);
        if (durationStopped || terminalStopped) {
            child.notificationIntervalStopped = true;
            persistInterval(child);
            return;
        }
        long dueTick = elapsed(now, child.notificationWorkStartedAt) / interval;
        if (dueTick <= child.notificationIntervalTick) return;
        long coalesced = dueTick - child.notificationIntervalTick;
        child.notificationIntervalTick = dueTick;
        child.generation++;
        child.events.addObject().put("sequence", ++child.eventSequence)
                .put("kind", "interval_notification").put("state", child.notificationWorkState)
                .put("coalesced_ticks", coalesced).put("source_child_id", child.id)
                .put("target_parent_id", parentId).put("created_at_ms", now);
        if (child.events.size() > MAX_EVENTS) child.events.remove(0);
        persistInterval(child);
        child.notifyAll();
    }

    private void persistInterval(Child child) throws IOException {
        try {
            persist(child);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("could not persist interval notification", failure);
        }
    }

    private boolean terminalStopEnabled(Child child) {
        JsonNode stops = child.notifications.get("stop_conditions");
        if (stops == null) return true;
        for (JsonNode stop : stops) if (stop.asText().equals("terminal")) return true;
        return false;
    }

    private static long elapsed(long now, long started) {
        return now <= started ? 0 : now - started;
    }

    private String renderParentEnvelope(ArrayNode deliveries) throws IOException {
        StringBuilder content = new StringBuilder(
                "<subagent_deliveries trusted_runtime_context=\"true\">\n");
        for (JsonNode delivery : deliveries) {
            content.append("- ").append(json.writeValueAsString(delivery)).append('\n');
        }
        return content.append("</subagent_deliveries>\n").toString();
    }

    String execute(SubagentCommand command, String invocationId) throws Exception {
        return execute(command, invocationId, null);
    }

    String execute(SubagentCommand command, String invocationId, String actorId) throws Exception {
        if (command.branch().equals("inspect") || authenticatedMilestone(command, actorId)) {
            return executeOperation(command, invocationId, actorId);
        }
        synchronized (this) {
            return executeOperation(command, invocationId, actorId);
        }
    }

    private String executeOperation(SubagentCommand command, String invocationId, String actorId) throws Exception {
        String operationId = operationId(invocationId);
        String replayKey = replayKey(operationId, actorId);
        String fingerprint = requestFingerprint(command);
        OperationReplay replay;
        synchronized (operationMutex) { replay = operations.get(replayKey); }
        if (replay != null) {
            if (replay.fingerprint().equals(fingerprint)) return replay.receipt();
            return failure(operationId, null, "operation_conflict", false);
        }
        String result;
        try {
            switch (command.branch()) {
                case "create": result = create(operationId, command.value()); break;
                case "inspect": result = inspect(operationId, command.value()); break;
                case "message": result = message(operationId, command.value(), actorId); break;
                case "relationship": result = relationship(operationId, command.value()); break;
                case "configure": result = configure(operationId, command.value()); break;
                case "lifecycle": result = lifecycle(operationId, command.value()); break;
                default: result = failure(operationId, null, "invalid_branch_selection", false); break;
            }
        } catch (SubagentFailure failure) {
            result = failure(operationId, failure.childId, failure.code, failure.retryable);
        }
        remember(replayKey, fingerprint, result);
        return result;
    }

    private void remember(String operationId, String fingerprint, String receipt) throws IOException {
        synchronized (operationMutex) {
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
    }

    private String requestFingerprint(SubagentCommand command) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(command.branch().getBytes(StandardCharsets.UTF_8));
            hashNode(hash, command.value());
            return Hex.encode(hash.digest());
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
            if (value.path("notifications").isObject()) {
                child.notifications = ((ObjectNode) value.path("notifications")).deepCopy();
            }
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
                    case "status":
                        requested.set("status", status(child));
                        break;
                    case "messages": {
                        MessagePage page = messages(child, value);
                        requested.set("messages", page.values());
                        nextCursor = page.nextCursor();
                        break;
                    }
                    case "tool_activity":
                        requested.set("tool_activity", child.toolActivity.deepCopy());
                        break;
                    case "events":
                        requested.set("events", child.events.deepCopy());
                        break;
                    case "configuration":
                        requested.set("configuration", configuration(child));
                        break;
                    case "relationship":
                        requested.set("relationship", relationship(child));
                        break;
                    default:
                        break;
                }
            }
        }
        String status = timedOut ? "wait_timed_out" : child.state;
        return outcome(true, operationId, child.id, status, null, timedOut, requested, nextCursor);
    }

    private String message(String operationId, ObjectNode value, String actorId) throws Exception {
        if (value.has("milestone")) {
            return milestone(operationId, value.path("milestone").path("name").asText(), actorId);
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

    private String milestone(String operationId, String name, String actorId) throws Exception {
        if (actorId == null) throw new SubagentFailure(null, "invalid_milestone_caller", false);
        Child child = child(actorId);
        synchronized (child) {
            if (!child.state.equals("running") || child.currentPrompt == null) {
                throw new SubagentFailure(child.id, "no_active_work", false);
            }
            boolean declared = false;
            for (JsonNode candidate : child.notifications.path("milestones")) {
                if (candidate.asText().equals(name)) { declared = true; break; }
            }
            if (!declared) throw new SubagentFailure(child.id, "undeclared_milestone", false);
            Long sequence = child.emittedMilestones.get(name);
            if (sequence == null) {
                child.generation++;
                sequence = ++child.eventSequence;
                ObjectNode event = child.events.addObject().put("sequence", sequence)
                        .put("kind", "milestone_emitted").put("name", name)
                        .put("source_child_id", child.id).put("created_at_ms", System.currentTimeMillis());
                if (child.parentId == null) event.putNull("target_parent_id");
                else event.put("target_parent_id", child.parentId);
                if (child.events.size() > MAX_EVENTS) child.events.remove(0);
                child.emittedMilestones.put(name, sequence);
                persistQuietly(child);
                child.notifyAll();
            }
            ObjectNode requested = json.createObjectNode().put("outcome", "milestone_emitted")
                    .put("name", name).put("source_child_id", child.id)
                    .put("generation", child.generation).put("event_sequence", sequence);
            return outcome(true, operationId, child.id, "milestone_emitted", null, false, requested, null);
        }
    }

    private String configure(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        synchronized (child) {
            if (!child.state.equals("idle")) {
                throw new SubagentFailure(child.id, "invalid_state", false);
            }
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
            if (value.path("notifications").isObject()) {
                child.notifications = ((ObjectNode) value.path("notifications")).deepCopy();
            }
            changed(child, "configured");
        }
        return receipt(operationId, child, "configured");
    }

    private String relationship(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        synchronized (child) {
            if (Set.of("running", "awaiting_approval").contains(child.state)) {
                throw new SubagentFailure(child.id, "invalid_state", false);
            }
            String action = value.path("action").asText();
            switch (action) {
                case "detach": {
                    if (child.parentId == null) {
                        throw new SubagentFailure(child.id, "relationship_missing_parent", false);
                    }
                    child.parentId = null;
                    break;
                }
                case "attach": {
                    if (child.parentId != null) {
                        throw new SubagentFailure(child.id, "relationship_already_parented", false);
                    }
                    String parent = value.path("parent_id").asText("root");
                    if (!parent.equals("root")) child(parent);
                    if (createsCycle(child.id, parent)) {
                        throw new SubagentFailure(child.id, "relationship_cycle", false);
                    }
                    child.parentId = parent;
                    break;
                }
                case "reparent": {
                    if (child.parentId == null) {
                        throw new SubagentFailure(child.id, "relationship_missing_parent", false);
                    }
                    String parent = value.path("parent_id").asText();
                    child(parent);
                    if (createsCycle(child.id, parent)) {
                        throw new SubagentFailure(child.id, "relationship_cycle", false);
                    }
                    child.parentId = parent;
                    break;
                }
                default:
                    throw new SubagentFailure(child.id, "invalid_relationship", false);
            }
            changed(child, "relationship_changed");
        }
        return receipt(operationId, child, "relationship_changed");
    }

    private String lifecycle(String operationId, ObjectNode value) throws Exception {
        Child child = child(value.path("id").asText());
        String action = value.path("action").asText();
        boolean cancelled = false;
        synchronized (child) {
            switch (action) {
                case "cancel": {
                    if (!Set.of("queued", "running", "interrupted").contains(child.state)) {
                        throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    }
                    if (child.future != null) child.future.cancel(true);
                    child.state = child.mode.equals("persistent") ? "idle" : "cancelled";
                    child.notificationWorkState = "cancelled";
                    child.currentPrompt = null;
                    cancelled = true;
                    break;
                }
                case "resume": {
                    if (!child.state.equals("interrupted")) {
                        throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    }
                    child.state = child.queue.isEmpty() ? "idle" : "queued";
                    if (!child.queue.isEmpty()) submitNext(child);
                    break;
                }
                case "close": {
                    if (child.state.equals("archived")) throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    child.archivedFrom = child.state;
                    if (child.future != null) child.future.cancel(true);
                    child.state = "archived";
                    break;
                }
                case "reopen": {
                    if (!child.state.equals("archived")) throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
                    child.state = child.queue.isEmpty() ? terminalOrIdle(child.archivedFrom) : "queued";
                    if (!child.queue.isEmpty()) submitNext(child);
                    break;
                }
                default:
                    throw new SubagentFailure(child.id, "invalid_lifecycle_transition", false);
            }
            changed(child, action);
            if (cancelled) notifyTerminal(child, "cancelled");
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
        child.emittedMilestones.clear();
        child.notificationWorkStartedAt = -1;
        child.notificationIntervalTick = 0;
        child.notificationIntervalStopped = false;
        child.notificationWorkState = "queued";
        child.state = "queued";
        changed(child, "queued");
        child.future = executor.submit(() -> run(child, prompt));
    }

    private void run(Child child, String prompt) {
        synchronized (child) {
            if (child.state.equals("archived")) return;
            child.state = "running";
            child.notificationWorkStartedAt = clock.getAsLong();
            child.notificationWorkState = "running";
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
                child.notificationWorkState = "completed";
                child.state = child.mode.equals("one_off") ? "completed" : "idle";
                changed(child, child.state);
                notifyTerminal(child, "completed");
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
                child.failure = safeFailure("", failure);
                child.currentPrompt = null;
                child.notificationWorkState = "failed";
                child.state = "failed";
                changed(child, "failed");
                notifyTerminal(child, "failed");
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
        result.set("notifications", child.notifications.deepCopy());
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

    private void notifyTerminal(Child child, String terminal) {
        if (!terminalEnabled(child, terminal) || child.parentId == null) return;
        child.generation++;
        ObjectNode event = child.events.addObject().put("sequence", ++child.eventSequence)
                .put("kind", "terminal_notification").put("terminal", terminal)
                .put("source_child_id", child.id).put("target_parent_id", child.parentId)
                .put("created_at_ms", System.currentTimeMillis());
        if (child.failure != null) event.put("failure_reason", child.failure);
        if (child.events.size() > MAX_EVENTS) child.events.remove(0);
        persistQuietly(child);
        child.notifyAll();
    }

    private boolean terminalEnabled(Child child, String terminal) {
        JsonNode configured = child.notifications.path("terminal").get(terminal);
        return configured == null || configured.asBoolean();
    }

    private static String safeFailure(String prefix, Throwable failure) {
        String detail = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
        String redacted = SecretRedactor.mask(prefix + detail);
        return redacted.length() <= 1024 ? redacted : redacted.substring(0, 1024);
    }

    private static String operationId(String invocation) {
        if (invocation != null && !invocation.isEmpty() && invocation.length() <= 128
                && invocation.chars().noneMatch(Character::isWhitespace)) return invocation;
        return "call_" + digest(invocation == null ? "" : invocation);
    }

    private boolean authenticatedMilestone(SubagentCommand command, String actorId) {
        return actorId != null && command.branch().equals("message") && command.value().has("milestone");
    }

    private String replayKey(String operationId, String actorId) {
        return actorId == null ? operationId : "actor-" + digest(actorId + "\0" + operationId);
    }

    private static String digest(String value) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256")
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
    static final class ChildConfiguration {
        private final String id;
        private final String name;
        private final String model;
        private final String effort;
        private final PermissionMode permissionMode;

        ChildConfiguration(String id, String name, String model, String effort, PermissionMode permissionMode) {
            this.id = id;
            this.name = name;
            this.model = model;
            this.effort = effort;
            this.permissionMode = permissionMode;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String model() { return model; }
        public String effort() { return effort; }
        public PermissionMode permissionMode() { return permissionMode; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ChildConfiguration)) return false;
            ChildConfiguration that = (ChildConfiguration) other;
            return Objects.equals(id, that.id) && Objects.equals(name, that.name)
                    && Objects.equals(model, that.model) && Objects.equals(effort, that.effort)
                    && permissionMode == that.permissionMode;
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(id);
            result = 31 * result + Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(model);
            result = 31 * result + Objects.hashCode(effort);
            result = 31 * result + Objects.hashCode(permissionMode);
            return result;
        }

        @Override
        public String toString() {
            return "ChildConfiguration[id=" + id + ", name=" + name + ", model=" + model
                    + ", effort=" + effort + ", permissionMode=" + permissionMode + "]";
        }
    }

    private static final class Message {
        private final String role;
        private final String content;
        private final long createdAtMs;

        private Message(String role, String content, long createdAtMs) {
            this.role = role;
            this.content = content;
            this.createdAtMs = createdAtMs;
        }

        public String role() { return role; }
        public String content() { return content; }
        public long createdAtMs() { return createdAtMs; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Message)) return false;
            Message that = (Message) other;
            return createdAtMs == that.createdAtMs && Objects.equals(role, that.role)
                    && Objects.equals(content, that.content);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(role);
            result = 31 * result + Objects.hashCode(content);
            result = 31 * result + Long.hashCode(createdAtMs);
            return result;
        }

        @Override
        public String toString() {
            return "Message[role=" + role + ", content=" + content + ", createdAtMs=" + createdAtMs + "]";
        }
    }

    private static final class MessagePage {
        private final ArrayNode values;
        private final String nextCursor;

        private MessagePage(ArrayNode values, String nextCursor) {
            this.values = values;
            this.nextCursor = nextCursor;
        }

        public ArrayNode values() { return values; }
        public String nextCursor() { return nextCursor; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MessagePage)) return false;
            MessagePage that = (MessagePage) other;
            return Objects.equals(values, that.values) && Objects.equals(nextCursor, that.nextCursor);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(values);
            result = 31 * result + Objects.hashCode(nextCursor);
            return result;
        }

        @Override
        public String toString() {
            return "MessagePage[values=" + values + ", nextCursor=" + nextCursor + "]";
        }
    }

    private static final class OperationReplay {
        private final String fingerprint;
        private final String receipt;

        private OperationReplay(String fingerprint, String receipt) {
            this.fingerprint = fingerprint;
            this.receipt = receipt;
        }

        public String fingerprint() { return fingerprint; }
        public String receipt() { return receipt; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof OperationReplay)) return false;
            OperationReplay that = (OperationReplay) other;
            return Objects.equals(fingerprint, that.fingerprint) && Objects.equals(receipt, that.receipt);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(fingerprint);
            result = 31 * result + Objects.hashCode(receipt);
            return result;
        }

        @Override
        public String toString() {
            return "OperationReplay[fingerprint=" + fingerprint + ", receipt=" + receipt + "]";
        }
    }

    private final class Child {
        final String id;
        final String mode;
        final ChildRunner runner;
        final ArrayDeque<String> queue = new ArrayDeque<>();
        final List<Message> messages = new ArrayList<>();
        final ArrayNode events = json.createArrayNode();
        final ArrayNode toolActivity = json.createArrayNode();
        final Map<String, Long> emittedMilestones = new HashMap<>();
        String name;
        ChildConfiguration configuration;
        String state = "idle";
        String archivedFrom = "idle";
        String parentId = "root";
        String failure;
        String currentPrompt;
        long generation = 1;
        long eventSequence;
        long parentDeliverySequence;
        long notificationWorkStartedAt = -1;
        long notificationIntervalTick;
        boolean notificationIntervalStopped;
        String notificationWorkState = "idle";
        Future<?> future;
        ObjectNode notifications = json.createObjectNode();

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
