package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Process-lifetime monitor state for the compact fx terminal contract. */
final class TerminalMonitors {
    private static final int MAX_MONITORS = 32;
    private static final int MAX_EVENTS = 256;
    private static final Set<String> OPERATIONS = Set.of("add", "update", "pause", "resume", "remove");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    private final WorkspaceTools.Workspace workspace;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private long nextMonitorId = 1;
    private long nextEventId = 1;

    TerminalMonitors(WorkspaceTools.Workspace workspace) {
        this.workspace = workspace;
    }

    static List<TerminalMonitorDefinition> parseInitial(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > MAX_MONITORS) throw invalid("InvalidMonitor");
        List<TerminalMonitorDefinition> definitions = new ArrayList<>();
        value.forEach(item -> definitions.add(TerminalMonitorDefinition.parse(item)));
        return List.copyOf(definitions);
    }

    synchronized void addInitial(List<TerminalMonitorDefinition> definitions) throws IOException {
        for (TerminalMonitorDefinition definition : definitions) add(definition);
    }

    synchronized String apply(JsonNode operation) throws IOException {
        if (operation == null || !operation.isObject()) throw invalid("InvalidMonitor");
        exact(operation, Set.of("kind", "monitor_id", "definition"));
        String kind = text(operation, "kind");
        if (!OPERATIONS.contains(kind)) throw invalid("InvalidMonitor");
        switch (kind) {
            case "add":
                return add(TerminalMonitorDefinition.parse(required(operation, "definition")));
            case "update":
                return update(text(operation, "monitor_id"),
                        TerminalMonitorDefinition.parse(required(operation, "definition")));
            case "pause":
                return state(text(operation, "monitor_id"), true);
            case "resume":
                return state(text(operation, "monitor_id"), false);
            case "remove":
                return remove(text(operation, "monitor_id"));
            default:
                throw invalid("InvalidMonitor");
        }
    }

    synchronized void refresh(Observation observation) {
        long now = System.currentTimeMillis();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            Long duration = entry.definition.lifetime().durationMs();
            if (duration != null && now - entry.createdAtMs >= duration) {
                emit(entry, "expired", now);
                iterator.remove();
                continue;
            }
            if (entry.paused) continue;
            boolean due = !entry.definition.condition().polling()
                    || now - entry.lastCheckMs >= entry.definition.checkIntervalMs();
            if (!due) continue;
            entry.lastCheckMs = now;
            entry.checkCount++;
            boolean matched;
            try {
                matched = matches(entry, observation);
            } catch (Exception failure) {
                if (!entry.degraded) emit(entry, "degraded", now);
                entry.degraded = true;
                continue;
            }
            if (entry.degraded) {
                entry.degraded = false;
                emit(entry, "recovered", now);
            }
            boolean transition = matched != entry.matched;
            boolean notify = shouldNotify(entry, matched, transition, observation.exited(), now);
            entry.matched = matched;
            if (notify) emit(entry, matched ? "matched" : observation.exited() ? "session_exit" : "condition_lost", now);
            if ((matched && entry.definition.lifetime().kind().equals("until_match"))
                    || (observation.exited() && entry.definition.lifetime().kind().equals("until_session_end"))) {
                iterator.remove();
            }
        }
    }

    synchronized ArrayNode snapshots(ObjectMapper json) {
        ArrayNode result = json.createArrayNode();
        for (Entry entry : entries.values()) {
            result.addObject().put("monitor_id", entry.id)
                    .put("state", entry.degraded ? "degraded" : entry.paused ? "paused" : "active");
        }
        return result;
    }

    synchronized ArrayNode inspectEvents(ObjectMapper json, long afterEventId, Long acknowledgeEventId, int maximum) {
        if (acknowledgeEventId != null) events.removeIf(event -> event.id <= acknowledgeEventId);
        ArrayNode result = json.createArrayNode();
        for (Event event : events) {
            if (event.id <= afterEventId) continue;
            result.addObject().put("event_id", event.id).put("monitor_id", event.monitorId)
                    .put("reason", event.reason).put("created_at_ms", event.createdAtMs);
            if (result.size() >= maximum) break;
        }
        return result;
    }

    synchronized int size() { return entries.size(); }

    private String add(TerminalMonitorDefinition definition) throws IOException {
        if (entries.size() >= MAX_MONITORS) throw invalid("MonitorCapacityExceeded");
        String id = "monitor-" + nextMonitorId++;
        Entry entry = new Entry(id, definition, System.currentTimeMillis());
        initializeBaseline(entry);
        entries.put(id, entry);
        return id;
    }

    private String update(String id, TerminalMonitorDefinition definition) throws IOException {
        Entry current = entry(id);
        Entry replacement = new Entry(id, definition, System.currentTimeMillis());
        replacement.generation = current.generation + 1;
        initializeBaseline(replacement);
        entries.put(id, replacement);
        return id;
    }

    private String state(String id, boolean paused) {
        Entry entry = entry(id);
        if (entry.paused != paused) {
            entry.paused = paused;
            if (entry.definition.notification().kind().equals("on_state_change")) {
                emit(entry, paused ? "paused" : "resumed", System.currentTimeMillis());
            }
        }
        return id;
    }

    private String remove(String id) {
        entry(id);
        entries.remove(id);
        return id;
    }

    private Entry entry(String id) {
        if (id == null || !id.matches("monitor-[1-9][0-9]*") || !entries.containsKey(id)) {
            throw invalid("InvalidMonitor");
        }
        return entries.get(id);
    }

    private boolean shouldNotify(Entry entry, boolean matched, boolean transition, boolean exited, long now) {
        TerminalMonitorDefinition.Notify schedule = entry.definition.notification();
        switch (schedule.kind()) {
            case "on_match":
                return matched && !entry.matched;
            case "on_state_change":
                return transition;
            case "on_exit":
                return exited;
            case "every_check":
                return true;
            case "every_n_checks":
                return entry.checkCount % schedule.value() == 0;
            case "interval":
                boolean due = now - entry.lastNotificationMs >= schedule.value();
                if (due) entry.lastNotificationMs = now;
                return due;
            default:
                return false;
        }
    }

    private boolean matches(Entry entry, Observation observation) throws Exception {
        TerminalMonitorDefinition.Condition condition = entry.definition.condition();
        switch (condition.kind()) {
            case "process_exit":
                return observation.exited();
            case "exit_code":
                return observation.exitCode() != null
                        && observation.exitCode().longValue() == condition.number();
            case "signal":
                return condition.text().equals(observation.signal());
            case "output_contains":
                return observation.output().contains(condition.text());
            case "output_matches":
            case "screen_matches":
                return glob(condition.text()).matcher(observation.output()).find();
            case "output_quiet":
                return System.nanoTime() - observation.lastOutputNanos()
                        >= TimeUnit.MILLISECONDS.toNanos(condition.number());
            case "tcp_ready":
                return tcpReady(condition.text(), condition.port());
            case "http_ready":
                return httpReady(condition.text());
            case "path_exists":
                return Files.exists(path(condition.text()), LinkOption.NOFOLLOW_LINKS);
            case "path_changed":
                return changed(entry, path(condition.text()));
            case "path_size":
                return Files.exists(path(condition.text()), LinkOption.NOFOLLOW_LINKS)
                        && Files.size(path(condition.text())) >= condition.number();
            case "custom_probe":
                return customProbe(condition.text(), condition.secondary());
            default:
                return false;
        }
    }

    private void initializeBaseline(Entry entry) throws IOException {
        TerminalMonitorDefinition.Condition condition = entry.definition.condition();
        switch (condition.kind()) {
            case "path_exists":
            case "path_size":
                path(condition.text());
                break;
            case "path_changed":
                entry.pathBaseline = fileState(path(condition.text()));
                break;
            case "custom_probe":
                workspace.resolveInsideExisting(condition.secondary());
                break;
            default:
                break;
        }
    }

    private boolean changed(Entry entry, Path path) throws IOException {
        FileState current = fileState(path);
        if (entry.pathBaseline == null) {
            entry.pathBaseline = current;
            return false;
        }
        return !entry.pathBaseline.equals(current);
    }

    private Path path(String value) throws IOException {
        return workspace.resolveInsideCandidate(value);
    }

    private boolean customProbe(String command, String cwdText) throws Exception {
        Path cwd = workspace.resolveInsideExisting(cwdText);
        ProcessBuilder builder = TerminalTool.processBuilderForMonitor(command);
        builder.directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().remove("JAVA_AGENT_API_KEY");
        builder.environment().remove("OPENAI_API_KEY");
        Process process = builder.start();
        boolean exited = process.waitFor(2, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        return exited && process.exitValue() == 0;
    }

    private static boolean tcpReady(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static boolean httpReady(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 400;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder("(?s)");
        for (int index = 0; index < pattern.length(); index++) {
            char value = pattern.charAt(index);
            if (value == '*') regex.append(".*");
            else if (value == '?') regex.append('.');
            else regex.append(Pattern.quote(Character.toString(value)));
        }
        return Pattern.compile(regex.toString());
    }

    private static FileState fileState(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new FileState(false, 0, 0);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return new FileState(true, attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private void emit(Entry entry, String reason, long now) {
        events.add(new Event(nextEventId++, entry.id, reason, now));
        if (events.size() > MAX_EVENTS) events.remove(0);
        entry.lastNotificationMs = now;
    }

    private static JsonNode required(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) throw invalid("InvalidMonitor");
        return value;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = required(input, field);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid("InvalidMonitor");
        return value.asText();
    }

    private static void exact(JsonNode input, Set<String> fields) {
        input.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw invalid("InvalidMonitor");
        });
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("terminal monitor arguments are invalid: " + reason);
    }

    static final class Observation {
        private final String output;
        private final boolean exited;
        private final Integer exitCode;
        private final String signal;
        private final long lastOutputNanos;

        Observation(String output, boolean exited, Integer exitCode, String signal, long lastOutputNanos) {
            this.output = output;
            this.exited = exited;
            this.exitCode = exitCode;
            this.signal = signal;
            this.lastOutputNanos = lastOutputNanos;
        }

        public String output() { return output; }
        public boolean exited() { return exited; }
        public Integer exitCode() { return exitCode; }
        public String signal() { return signal; }
        public long lastOutputNanos() { return lastOutputNanos; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Observation)) return false;
            Observation that = (Observation) other;
            return exited == that.exited && lastOutputNanos == that.lastOutputNanos
                    && Objects.equals(output, that.output) && Objects.equals(exitCode, that.exitCode)
                    && Objects.equals(signal, that.signal);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(output);
            result = 31 * result + Boolean.hashCode(exited);
            result = 31 * result + Objects.hashCode(exitCode);
            result = 31 * result + Objects.hashCode(signal);
            return 31 * result + Long.hashCode(lastOutputNanos);
        }

        @Override
        public String toString() {
            return "Observation[output=" + output + ", exited=" + exited + ", exitCode=" + exitCode
                    + ", signal=" + signal + ", lastOutputNanos=" + lastOutputNanos + "]";
        }
    }

    private static final class Event {
        private final long id;
        private final String monitorId;
        private final String reason;
        private final long createdAtMs;

        Event(long id, String monitorId, String reason, long createdAtMs) {
            this.id = id;
            this.monitorId = monitorId;
            this.reason = reason;
            this.createdAtMs = createdAtMs;
        }

        public long id() { return id; }
        public String monitorId() { return monitorId; }
        public String reason() { return reason; }
        public long createdAtMs() { return createdAtMs; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Event)) return false;
            Event that = (Event) other;
            return id == that.id && createdAtMs == that.createdAtMs
                    && Objects.equals(monitorId, that.monitorId) && Objects.equals(reason, that.reason);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(id);
            result = 31 * result + Objects.hashCode(monitorId);
            result = 31 * result + Objects.hashCode(reason);
            return 31 * result + Long.hashCode(createdAtMs);
        }

        @Override
        public String toString() {
            return "Event[id=" + id + ", monitorId=" + monitorId + ", reason=" + reason
                    + ", createdAtMs=" + createdAtMs + "]";
        }
    }

    private static final class FileState {
        private final boolean exists;
        private final long size;
        private final long modifiedMs;

        FileState(boolean exists, long size, long modifiedMs) {
            this.exists = exists;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }

        public boolean exists() { return exists; }
        public long size() { return size; }
        public long modifiedMs() { return modifiedMs; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FileState)) return false;
            FileState that = (FileState) other;
            return exists == that.exists && size == that.size && modifiedMs == that.modifiedMs;
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(exists);
            result = 31 * result + Long.hashCode(size);
            return 31 * result + Long.hashCode(modifiedMs);
        }

        @Override
        public String toString() {
            return "FileState[exists=" + exists + ", size=" + size + ", modifiedMs=" + modifiedMs + "]";
        }
    }

    private static final class Entry {
        final String id;
        final TerminalMonitorDefinition definition;
        final long createdAtMs;
        long generation = 1;
        long lastCheckMs;
        long lastNotificationMs;
        long checkCount;
        boolean matched;
        boolean paused;
        boolean degraded;
        FileState pathBaseline;

        Entry(String id, TerminalMonitorDefinition definition, long createdAtMs) {
            this.id = id;
            this.definition = definition;
            this.createdAtMs = createdAtMs;
            this.lastNotificationMs = createdAtMs;
        }
    }
}
