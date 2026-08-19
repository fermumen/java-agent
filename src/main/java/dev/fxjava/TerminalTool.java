package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java implementation of fx's compact terminal action contract.
 * It intentionally models a process pipe rather than claiming PTY semantics.
 */
final class TerminalTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_COMMAND_BYTES = 64 * 1024;
    private static final int MAX_WRITE_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_SESSIONS = 64;
    private static final List<String> ACTIONS = List.of("exec", "start", "read", "screen", "write",
            "wait", "monitor", "inspect", "list", "resize", "signal", "close");
    private static final List<String> PUBLIC_FIELDS = List.of("action", "session_id", "cwd", "command",
            "profile", "shell", "backend", "return_when", "wait_ceiling_ms", "dimensions",
            "initial_monitors", "cursor_segment", "cursor_offset", "after_event_id",
            "acknowledge_event_id", "max_events", "write", "lease", "monitor", "task_id",
            "workspace_root", "rows", "columns", "signal", "close_policy");
    private static final Map<String, Contract> CONTRACTS = contracts();

    private final WorkspaceTools.Workspace workspace;
    private final Tool capturedCommand;
    private final ObjectNode parameters;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, TerminalSession> sessions = new LinkedHashMap<>();

    TerminalTool(WorkspaceTools.Workspace workspace, Tool capturedCommand) {
        this.workspace = workspace;
        this.capturedCommand = capturedCommand;
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("action", enumSchema(ACTIONS));
        for (String field : PUBLIC_FIELDS) {
            if (field.equals("action")) continue;
            properties.set(field, fieldSchema(field));
        }
        schema.putArray("required").add("action");
        schema.put("additionalProperties", false);
        parameters = schema;
    }

    @Override public String name() { return "terminal"; }

    @Override public String description() {
        return "Execute captured commands or manage bounded background terminal processes. "
                + "Pure Java provides process pipes and bounded plain-output screens; "
                + "full PTY/ANSI screens and restart persistence remain unavailable.";
    }

    @Override public ObjectNode parameters() { return parameters; }

    @Override public boolean requiresApproval() { return false; }

    @Override
    public boolean requiresApproval(JsonNode arguments) throws Exception {
        String action = validate(arguments).path("action").asText();
        return switch (action) {
            case "read", "screen", "list" -> false;
            case "inspect" -> arguments.hasNonNull("acknowledge_event_id");
            default -> true;
        };
    }

    @Override public boolean isErrorResult(String result) { return result.startsWith("{\"failure\":"); }

    @Override
    public String preview(JsonNode arguments) {
        String action = arguments.path("action").asText("?");
        String target = arguments.path("command").isTextual() ? arguments.path("command").asText()
                : arguments.path("session_id").asText("");
        if (action.equals("monitor") && arguments.has("monitor")) target += " " + arguments.path("monitor");
        if (action.equals("start") && arguments.path("initial_monitors").size() > 0) {
            target += " monitors=" + arguments.path("initial_monitors");
        }
        if (target.length() > 120) target = target.substring(0, 120) + "...";
        return "terminal " + action + (target.isBlank() ? "" : " `" + target + "`");
    }

    @Override
    public String execute(JsonNode rawArguments) throws Exception {
        ObjectNode arguments = validate(rawArguments);
        String action = arguments.path("action").asText();
        return switch (action) {
            case "exec" -> executeCaptured(arguments);
            case "start" -> start(arguments);
            case "read" -> read(arguments);
            case "screen" -> screen(arguments);
            case "write" -> write(arguments);
            case "wait" -> waitFor(arguments);
            case "monitor" -> monitor(arguments);
            case "inspect" -> inspect(arguments);
            case "list" -> list(arguments);
            case "resize" -> resize(arguments);
            case "signal" -> signal(arguments);
            case "close" -> close(arguments);
            default -> throw new IllegalArgumentException("terminal arguments must match the advertised action schema");
        };
    }

    private String executeCaptured(ObjectNode arguments) throws Exception {
        ObjectNode translated = JSON.createObjectNode().put("command", text(arguments, "command"));
        if (arguments.path("cwd").isTextual()) {
            translated.put("working_directory", arguments.path("cwd").asText());
        }
        return capturedCommand.execute(translated);
    }

    private String start(ObjectNode arguments) throws Exception {
        String backend = arguments.path("backend").asText("native");
        if (!backend.equals("native")) return failure("start", null, "unsupported_host", false);
        List<TerminalMonitorDefinition> initialDefinitions = TerminalMonitors.parseInitial(
                composite(arguments, "initial_monitors"));
        TerminalMonitors monitors = new TerminalMonitors(workspace);
        monitors.addInitial(initialDefinitions);
        synchronized (sessions) {
            if (sessions.size() >= MAX_SESSIONS) return failure("start", null, "capacity_exceeded", true);
        }
        String command = arguments.path("command").isTextual() ? arguments.path("command").asText() : null;
        if (command != null && command.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) {
            throw new IllegalArgumentException("terminal start arguments are invalid: InvalidCommand");
        }
        Path cwd = workspace.resolveExisting(arguments.path("cwd").asText("."));
        if (!Files.isDirectory(cwd)) throw new IOException("Not a directory: " + workspace.display(cwd));
        ProcessBuilder builder = processBuilder(command);
        builder.directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().remove("JAVA_AGENT_API_KEY");
        builder.environment().remove("OPENAI_API_KEY");
        Process process;
        try {
            process = builder.start();
        } catch (IOException startup) {
            return failure("start", null, "startup_failed", false);
        }
        String id = "terminal-" + nextId.getAndIncrement();
        TerminalSession session = new TerminalSession(id, command, cwd, process, monitors);
        synchronized (sessions) { sessions.put(id, session); }
        session.startReader();
        session.startMonitorLoop();

        ObjectNode outcome = awaitCondition(session, composite(arguments, "return_when"),
                longValue(arguments, "wait_ceiling_ms", 0));
        refreshMonitors(session);
        Success response = success("start");
        response.body.set("session", facts(session));
        response.body.set("outcome", outcome);
        return stringify(response.root);
    }

    private String read(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        refreshMonitors(session);
        long segment = longValue(arguments, "cursor_segment", -1);
        if (segment != 1) return failure("read", session.id, "cursor_gap", false);
        long offset = longValue(arguments, "cursor_offset", 0);
        byte[] output = session.outputFrom(offset);
        long end = offset + output.length;
        Success response = success("read");
        response.body.set("session", facts(session));
        response.body.put("output", new String(output, StandardCharsets.UTF_8));
        if (output.length > 0) response.body.set("raw_range", range(offset, end));
        else response.body.putNull("raw_range");
        return stringify(response.root);
    }

    private String screen(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        refreshMonitors(session);
        ObjectNode snapshot = session.truncated ? null
                : TerminalScreen.render(JSON, session.outputText(), session.rows, session.columns);
        if (snapshot == null) return failure("screen", session.id, "screen_unavailable", false);
        Success response = success("screen");
        response.body.set("session", facts(session));
        response.body.set("snapshot", snapshot);
        return stringify(response.root);
    }

    private String write(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        JsonNode payload = composite(arguments, "write");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("terminal write arguments are invalid: InvalidWritePayload");
        }
        byte[] bytes = writeBytes(payload);
        if (bytes.length == 0 || bytes.length > MAX_WRITE_BYTES) {
            throw new IllegalArgumentException("terminal write arguments are invalid: InvalidWritePayload");
        }
        synchronized (session.stdin) {
            session.stdin.write(bytes);
            session.stdin.flush();
        }
        Success response = success("write");
        response.body.set("session", facts(session));
        response.body.put("accepted_bytes", bytes.length);
        return stringify(response.root);
    }

    private String waitFor(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        long ceiling = longValue(arguments, "wait_ceiling_ms", -1);
        if (ceiling < 1 || ceiling > Duration.ofMinutes(10).toMillis()) {
            throw new IllegalArgumentException("terminal wait arguments are invalid: InvalidWaitCeiling");
        }
        JsonNode condition = composite(arguments, "return_when");
        if (condition == null) throw new IllegalArgumentException("terminal wait arguments are invalid: InvalidReturnCondition");
        ObjectNode outcome = awaitCondition(session, condition, ceiling);
        refreshMonitors(session);
        Success response = success("wait");
        response.body.set("session", facts(session));
        response.body.set("outcome", outcome);
        return stringify(response.root);
    }

    private String inspect(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        refreshMonitors(session);
        Success response = success("inspect");
        response.body.set("session", facts(session));
        response.body.put("shell", shellName());
        response.body.put("cwd", session.cwd.toString());
        if (session.command == null) response.body.putNull("command"); else response.body.put("command", session.command);
        response.body.set("monitors", session.monitors.snapshots(JSON));
        long after = longValue(arguments, "after_event_id", 0);
        Long acknowledge = arguments.has("acknowledge_event_id")
                ? longValue(arguments, "acknowledge_event_id", 0) : null;
        int maximum = arguments.has("max_events") ? intValue(arguments, "max_events", 1, 256) : 256;
        response.body.set("events", session.monitors.inspectEvents(JSON, after, acknowledge, maximum));
        response.body.put("event_gap_through", 0);
        response.body.put("next_event_id", 1);
        return stringify(response.root);
    }

    private String monitor(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        refreshMonitors(session);
        String monitorId = session.monitors.apply(composite(arguments, "monitor"));
        Success response = success("monitor");
        response.body.set("session", facts(session));
        response.body.put("monitor_id", monitorId);
        return stringify(response.root);
    }

    private String list(ObjectNode arguments) throws Exception {
        String requestedWorkspace = arguments.path("workspace_root").isTextual()
                ? Path.of(arguments.path("workspace_root").asText()).toAbsolutePath().normalize().toString() : null;
        Success response = success("list");
        ArrayNode listed = response.body.putArray("sessions");
        synchronized (sessions) {
            for (TerminalSession session : sessions.values()) {
                refreshMonitors(session);
                if (requestedWorkspace == null || session.cwd.toString().startsWith(requestedWorkspace)) {
                    listed.add(facts(session));
                }
            }
        }
        return stringify(response.root);
    }

    private String resize(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        int rows = intValue(arguments, "rows", 1, 4096);
        int columns = intValue(arguments, "columns", 1, 4096);
        session.rows = rows;
        session.columns = columns;
        Success response = success("resize");
        response.body.set("session", facts(session));
        response.body.putObject("dimensions").put("rows", rows).put("columns", columns);
        return stringify(response.root);
    }

    private String signal(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        String signal = text(arguments, "signal");
        switch (signal) {
            case "kill" -> session.process.destroyForcibly();
            case "hangup", "interrupt", "quit", "terminate" -> session.process.destroy();
            default -> throw new IllegalArgumentException("terminal signal arguments are invalid: InvalidSignal");
        }
        session.lastSignal = signal;
        refreshMonitors(session);
        Success response = success("signal");
        response.body.set("session", facts(session));
        response.body.put("signal", signal);
        return stringify(response.root);
    }

    private String close(ObjectNode arguments) throws Exception {
        TerminalSession session = session(text(arguments, "session_id"));
        String policy = text(arguments, "close_policy");
        if (policy.equals("force")) session.process.destroyForcibly();
        else if (policy.equals("graceful")) {
            session.process.destroy();
            if (!session.process.waitFor(250, TimeUnit.MILLISECONDS)) session.process.destroyForcibly();
        } else throw new IllegalArgumentException("terminal close arguments are invalid: InvalidClosePolicy");
        session.closed = true;
        Success response = success("close");
        response.body.set("session", facts(session));
        response.body.put("policy", policy);
        synchronized (sessions) { sessions.remove(session.id); }
        return stringify(response.root);
    }

    private ObjectNode awaitCondition(TerminalSession session, JsonNode condition, long ceilingMs)
            throws InterruptedException {
        if (condition == null || condition.isMissingNode() || condition.isNull()) return unitOutcome("started");
        String kind = condition.path("kind").asText();
        if (kind.equals("started")) return unitOutcome("started");
        long deadline = ceilingMs <= 0 ? System.nanoTime()
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ceilingMs);
        if (kind.equals("exit")) {
            boolean exited = ceilingMs > 0 && session.process.waitFor(ceilingMs, TimeUnit.MILLISECONDS);
            if (exited) {
                session.joinReader();
                return JSON.createObjectNode().put("exited", session.process.exitValue());
            }
            return unitOutcome("safety_ceiling");
        }
        if (kind.equals("match")) {
            String pattern = condition.path("pattern").asText();
            if (pattern.isBlank() || pattern.length() > 4096) return unitOutcome("safety_ceiling");
            while (System.nanoTime() <= deadline) {
                if (session.outputText().contains(pattern)) return unitOutcome("condition_met");
                if (!session.process.isAlive()) break;
                Thread.sleep(10);
            }
            return session.process.isAlive() ? unitOutcome("safety_ceiling")
                    : JSON.createObjectNode().put("exited", session.process.exitValue());
        }
        if (kind.equals("quiet")) {
            long duration = condition.path("duration_ms").asLong(0);
            if (duration < 1) return unitOutcome("safety_ceiling");
            while (System.nanoTime() <= deadline) {
                if (System.nanoTime() - session.lastOutputNanos >= TimeUnit.MILLISECONDS.toNanos(duration)) {
                    return unitOutcome("condition_met");
                }
                if (!session.process.isAlive()) return JSON.createObjectNode().put("exited", session.process.exitValue());
                Thread.sleep(10);
            }
            return unitOutcome("safety_ceiling");
        }
        throw new IllegalArgumentException("terminal return_when is invalid");
    }

    private TerminalSession session(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("terminal session_not_found: " + id);
        }
        synchronized (sessions) {
            TerminalSession session = sessions.get(id);
            if (session == null) throw new IOException("terminal session_not_found: " + id);
            return session;
        }
    }

    private ObjectNode facts(TerminalSession session) {
        ObjectNode facts = JSON.createObjectNode();
        facts.put("session_id", session.id);
        facts.put("lifecycle", session.lifecycle());
        facts.putObject("attention").put("attention", "background").put("write_lease", "none");
        facts.put("backend", "native").put("persistence", "process_lifetime");
        facts.set("output_cursor", cursor(session.outputSize()));
        facts.putNull("unread_range").putNull("raw_gap");
        facts.putObject("screen_recovery").putObject("unavailable");
        facts.put("active_monitor_count", session.monitors.size());
        facts.putObject("next_actions");
        return facts;
    }

    private Success success(String action) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode body = root.putObject("success").putObject(action);
        return new Success(root, body);
    }

    private String failure(String action, String sessionId, String code, boolean retryable) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode failure = root.putObject("failure").put("action", action).put("code", code)
                .put("retryable", retryable);
        if (sessionId == null) failure.putNull("session_id"); else failure.put("session_id", sessionId);
        return JSON.writeValueAsString(root);
    }

    private static String stringify(JsonNode root) throws IOException {
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode cursor(long offset) {
        return JSON.createObjectNode().put("segment", 1).put("offset", offset);
    }

    private static ObjectNode range(long start, long end) {
        ObjectNode range = JSON.createObjectNode();
        range.set("start", cursor(start));
        range.set("end", cursor(end));
        return range;
    }

    private static ObjectNode unitOutcome(String kind) {
        ObjectNode result = JSON.createObjectNode();
        result.putObject(kind);
        return result;
    }

    private static ProcessBuilder processBuilder(String command) {
        if (windows()) {
            String shell = System.getenv().getOrDefault("COMSPEC", "cmd.exe");
            return command == null ? new ProcessBuilder(shell, "/d", "/q")
                    : new ProcessBuilder(shell, "/d", "/s", "/c", command);
        }
        String shell = System.getenv().getOrDefault("SHELL", "/bin/sh");
        return command == null ? new ProcessBuilder(shell) : new ProcessBuilder(shell, "-lc", command);
    }

    static ProcessBuilder processBuilderForMonitor(String command) {
        return processBuilder(command);
    }

    private static void refreshMonitors(TerminalSession session) {
        boolean exited = !session.process.isAlive();
        Integer exitCode = exited ? session.process.exitValue() : null;
        session.monitors.refresh(new TerminalMonitors.Observation(session.outputText(), exited,
                exitCode, session.lastSignal, session.lastOutputNanos));
    }

    private static String shellName() {
        return windows() ? System.getenv().getOrDefault("COMSPEC", "cmd.exe")
                : System.getenv().getOrDefault("SHELL", "/bin/sh");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static byte[] writeBytes(JsonNode payload) {
        String kind = payload.path("kind").asText();
        String value;
        switch (kind) {
            case "text", "paste" -> value = payload.path("text").asText("");
            case "keys" -> {
                StringBuilder result = new StringBuilder();
                for (JsonNode key : payload.path("keys")) result.append(keySequence(key.asText()));
                value = result.toString();
            }
            case "controls" -> {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                for (JsonNode control : payload.path("controls")) {
                    String character = control.asText();
                    if (character.length() != 1) throw new IllegalArgumentException("invalid control input");
                    char normalized = Character.toUpperCase(character.charAt(0));
                    if ((normalized < '@' || normalized > '_') && normalized != '?') {
                        throw new IllegalArgumentException("invalid control input");
                    }
                    result.write(normalized == '?' ? 127 : normalized & 0x1f);
                }
                return result.toByteArray();
            }
            default -> throw new IllegalArgumentException("invalid terminal write kind");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String keySequence(String key) {
        return switch (key) {
            case "enter" -> "\n";
            case "tab" -> "\t";
            case "escape" -> "\u001b";
            case "backspace" -> "\b";
            case "delete" -> "\u001b[3~";
            case "insert" -> "\u001b[2~";
            case "arrow_up" -> "\u001b[A";
            case "arrow_down" -> "\u001b[B";
            case "arrow_right" -> "\u001b[C";
            case "arrow_left" -> "\u001b[D";
            case "home" -> "\u001b[H";
            case "end" -> "\u001b[F";
            case "page_up" -> "\u001b[5~";
            case "page_down" -> "\u001b[6~";
            default -> throw new IllegalArgumentException("invalid named key: " + key);
        };
    }

    private static ObjectNode validate(JsonNode raw) throws IOException {
        if (!(raw instanceof ObjectNode input) || !input.path("action").isTextual()
                || !ACTIONS.contains(input.path("action").asText())) {
            throw new IllegalArgumentException("terminal arguments must match the advertised action schema");
        }
        ObjectNode arguments = input.deepCopy();
        for (String field : PUBLIC_FIELDS) if (arguments.path(field).isNull()) arguments.remove(field);
        String action = arguments.path("action").asText();
        Contract contract = CONTRACTS.get(action);
        List<String> invalid = new ArrayList<>();
        for (String field : PUBLIC_FIELDS) {
            if (arguments.has(field) && !contract.allowed.contains(field)) invalid.add(field);
        }
        arguments.fieldNames().forEachRemaining(field -> {
            if (!PUBLIC_FIELDS.contains(field)) invalid.add(field);
        });
        List<String> missing = contract.required.stream().filter(field -> !arguments.has(field)).toList();
        boolean conflict = action.equals("start") && arguments.has("profile") && arguments.has("shell");
        if (!invalid.isEmpty() || !missing.isEmpty() || conflict) {
            throw new IllegalArgumentException("terminal " + action + " invalid_action_fields: invalid=" + invalid
                    + ", missing=" + missing + (conflict ? ", conflict=profile,shell" : ""));
        }
        if (action.equals("exec")) {
            String command = text(arguments, "command");
            if (command.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) {
                throw new IllegalArgumentException("terminal exec arguments are invalid: InvalidCommand");
            }
        }
        return arguments;
    }

    private static JsonNode composite(ObjectNode arguments, String field) throws IOException {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) {
            JsonNode decoded = JSON.readTree(value.asText());
            if (decoded == null || (!decoded.isObject() && !decoded.isArray())) {
                throw new IllegalArgumentException(field + " must be an object or array");
            }
            return decoded;
        }
        return value;
    }

    private static String text(ObjectNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static long longValue(ObjectNode arguments, String field, long fallback) {
        JsonNode value = arguments.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || value.asLong() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.asLong();
    }

    private static int intValue(ObjectNode arguments, String field, int minimum, int maximum) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.canConvertToInt() || value.asInt() < minimum || value.asInt() > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return value.asInt();
    }

    private static ObjectNode fieldSchema(String field) {
        return switch (field) {
            case "cursor_segment", "cursor_offset", "after_event_id", "acknowledge_event_id",
                    "wait_ceiling_ms" -> JSON.createObjectNode().put("type", "integer")
                    .put("minimum", 0);
            case "max_events", "rows", "columns" -> JSON.createObjectNode().put("type", "integer")
                    .put("minimum", 1).put("maximum", field.equals("max_events") ? 256 : 4096);
            case "initial_monitors" -> JSON.createObjectNode().put("type", "array");
            case "shell", "return_when", "dimensions", "write", "monitor" ->
                    JSON.createObjectNode().put("type", "object");
            case "backend" -> enumSchema(List.of("native", "tmux"));
            case "signal" -> enumSchema(List.of("hangup", "interrupt", "quit", "terminate", "kill"));
            case "close_policy" -> enumSchema(List.of("graceful", "force"));
            case "lease" -> enumSchema(List.of("use", "acquire", "release"));
            default -> JSON.createObjectNode().put("type", "string");
        };
    }

    private static ObjectNode enumSchema(List<String> values) {
        ObjectNode schema = JSON.createObjectNode().put("type", "string");
        values.forEach(schema.putArray("enum")::add);
        return schema;
    }

    private static Map<String, Contract> contracts() {
        Map<String, Contract> result = new LinkedHashMap<>();
        add(result, "exec", fields("action", "command", "cwd", "profile"), fields("action", "command"));
        add(result, "start", fields("action", "cwd", "command", "profile", "shell", "backend", "return_when",
                "wait_ceiling_ms", "dimensions", "initial_monitors"), fields("action"));
        add(result, "read", fields("action", "session_id", "cursor_segment", "cursor_offset"),
                fields("action", "session_id", "cursor_segment"));
        add(result, "screen", fields("action", "session_id"), fields("action", "session_id"));
        add(result, "write", fields("action", "session_id", "write", "lease"), fields("action", "session_id"));
        add(result, "wait", fields("action", "session_id", "return_when", "wait_ceiling_ms"),
                fields("action", "session_id", "return_when", "wait_ceiling_ms"));
        add(result, "monitor", fields("action", "session_id", "monitor"), fields("action", "session_id", "monitor"));
        add(result, "inspect", fields("action", "session_id", "after_event_id", "acknowledge_event_id", "max_events"),
                fields("action", "session_id"));
        add(result, "list", fields("action", "task_id", "workspace_root", "backend"), fields("action"));
        add(result, "resize", fields("action", "session_id", "rows", "columns"),
                fields("action", "session_id", "rows", "columns"));
        add(result, "signal", fields("action", "session_id", "signal"), fields("action", "session_id", "signal"));
        add(result, "close", fields("action", "session_id", "close_policy"),
                fields("action", "session_id", "close_policy"));
        return Map.copyOf(result);
    }

    private static Set<String> fields(String... values) {
        return Set.copyOf(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static void add(Map<String, Contract> result, String action, Set<String> allowed, Set<String> required) {
        result.put(action, new Contract(allowed, required));
    }

    private record Contract(Set<String> allowed, Set<String> required) { }

    private record Success(ObjectNode root, ObjectNode body) { }

    private static final class TerminalSession {
        final String id;
        final String command;
        final Path cwd;
        final Process process;
        final OutputStream stdin;
        final TerminalMonitors monitors;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile long lastOutputNanos = System.nanoTime();
        volatile boolean truncated;
        volatile boolean closed;
        volatile String lastSignal;
        volatile int rows = 24;
        volatile int columns = 80;
        Thread reader;
        Thread monitorThread;

        TerminalSession(String id, String command, Path cwd, Process process, TerminalMonitors monitors) {
            this.id = id;
            this.command = command;
            this.cwd = cwd;
            this.process = process;
            this.stdin = process.getOutputStream();
            this.monitors = monitors;
        }

        void startReader() {
            reader = new Thread(() -> collect(process.getInputStream()), "java-agent-" + id + "-output");
            reader.setDaemon(true);
            reader.start();
        }

        void startMonitorLoop() {
            monitorThread = new Thread(() -> {
                while (!closed) {
                    if (!process.isAlive()) {
                        try { joinReader(); } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        refreshMonitors(this);
                        return;
                    }
                    refreshMonitors(this);
                    try { Thread.sleep(10); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "java-agent-" + id + "-monitors");
            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        private void collect(InputStream source) {
            try (source) {
                byte[] buffer = new byte[8192];
                for (int count; (count = source.read(buffer)) >= 0;) {
                    synchronized (output) {
                        int retained = Math.min(count, Math.max(0, MAX_OUTPUT_BYTES - output.size()));
                        if (retained > 0) output.write(buffer, 0, retained);
                        if (retained < count) truncated = true;
                        lastOutputNanos = System.nanoTime();
                    }
                }
            } catch (IOException ignored) {
                // Closing or signaling a process commonly closes its pipe first.
            }
        }

        byte[] outputFrom(long offset) {
            synchronized (output) {
                byte[] bytes = output.toByteArray();
                if (offset < 0 || offset > bytes.length) throw new IllegalArgumentException("cursor_offset is outside output");
                return Arrays.copyOfRange(bytes, (int) offset, bytes.length);
            }
        }

        String outputText() {
            synchronized (output) { return output.toString(StandardCharsets.UTF_8); }
        }

        int outputSize() {
            synchronized (output) { return output.size(); }
        }

        void joinReader() throws InterruptedException {
            if (reader != null) reader.join(1000);
        }

        String lifecycle() {
            if (closed) return "closed";
            return process.isAlive() ? "running" : "exited";
        }
    }
}
