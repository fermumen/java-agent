package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Compact Agent Client Protocol v1 JSON-RPC adapter. */
final class AcpServer {
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final Set<String> SESSION_METHODS = Set.of(
            "session/new", "session/load", "session/resume", "session/close", "session/list",
            "session/prompt", "session/cancel", "session/set_mode", "session/set_config_option");
    private final ObjectMapper json;
    private final Backend backend;
    private final ExecutorService prompts;
    private final Object outputLock = new Object();
    private final Object promptLock = new Object();
    private OutputStream output;
    private boolean initialized;
    private ActivePrompt activePrompt;

    AcpServer(ObjectMapper json, Backend backend) {
        this.json = json;
        this.backend = backend;
        this.prompts = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "java-agent-acp-prompt");
            thread.setDaemon(true);
            return thread;
        });
    }

    void serve(InputStream input, OutputStream output) throws Exception {
        this.output = output;
        FrameReader frames = new FrameReader(input);
        try {
            Frame frame;
            while ((frame = frames.next()) != null) {
                if (frame.overflow) {
                    error(null, -32000, "Request frame too large");
                    continue;
                }
                JsonNode message;
                try {
                    message = json.readTree(frame.bytes);
                } catch (Exception invalid) {
                    error(null, -32700, "Parse error");
                    continue;
                }
                dispatch(message);
            }
        } finally {
            cancelActive();
            prompts.shutdown();
            if (!prompts.awaitTermination(5, TimeUnit.SECONDS)) prompts.shutdownNow();
            backend.close();
        }
    }

    private void dispatch(JsonNode message) throws Exception {
        if (message == null || !message.isObject()
                || !message.path("jsonrpc").asText().equals("2.0")) {
            error(null, -32600, "Invalid Request");
            return;
        }
        boolean hasId = message.has("id");
        JsonNode id = hasId ? message.get("id").deepCopy() : null;
        JsonNode methodNode = message.get("method");
        if (methodNode == null) return; // A future client response, not a request.
        if (!methodNode.isTextual() || (hasId && !validId(id))) {
            error(null, -32600, "Invalid Request");
            return;
        }
        String method = methodNode.asText();
        if (!hasId) {
            if (initialized && method.equals("session/cancel")) cancelActive();
            return;
        }
        JsonNode params = message.has("params") ? message.get("params") : json.createObjectNode();

        if (method.equals("initialize")) {
            initialize(id, params);
            return;
        }
        if (!initialized) {
            error(id, -32600, "Not initialized. Call initialize first.");
            return;
        }
        if (method.equals("session/cancel")) {
            cancelActive();
            result(id, json.nullNode());
            return;
        }
        if (promptRunning() && !Set.of("session/new", "session/load", "session/resume", "session/close",
                "session/set_mode").contains(method)) {
            error(id, -32600, "Prompt already in progress");
            return;
        }
        if (!SESSION_METHODS.contains(method)) {
            error(id, -32601, "Method not found");
            return;
        }
        try {
            switch (method) {
                case "session/new" -> newSession(id, params);
                case "session/load" -> loadSession(id, params, false);
                case "session/resume" -> loadSession(id, params, true);
                case "session/close" -> closeSession(id, params);
                case "session/list" -> listSessions(id);
                case "session/prompt" -> startPrompt(id, params);
                case "session/set_mode" -> setMode(id, params);
                case "session/set_config_option" -> setConfig(id, params);
                default -> error(id, -32601, "Method not found");
            }
        } catch (IllegalArgumentException invalid) {
            error(id, -32602, safeMessage(invalid));
        } catch (Exception failure) {
            error(id, -32603, safeMessage(failure));
        }
    }

    private void initialize(JsonNode id, JsonNode params) throws IOException {
        if (initialized) {
            error(id, -32600, "Already initialized");
            return;
        }
        if (!params.isObject() || !params.path("protocolVersion").canConvertToInt()
                || params.path("protocolVersion").asInt() < 0
                || params.path("protocolVersion").asInt() > 65_535) {
            error(id, -32602, "Invalid initialize params");
            return;
        }
        try {
            backend.initialize();
        } catch (Exception failure) {
            error(id, -32600, safeMessage(failure));
            return;
        }
        initialized = true;
        ObjectNode capabilities = json.createObjectNode().put("loadSession", true);
        capabilities.putObject("promptCapabilities").put("image", false).put("audio", false)
                .put("embeddedContext", true);
        capabilities.putObject("mcpCapabilities").put("http", backend.supportsMcpHttp())
                .put("sse", backend.supportsMcpSse());
        capabilities.putObject("sessionCapabilities").putObject("list");
        ((ObjectNode) capabilities.path("sessionCapabilities")).putObject("resume");
        ((ObjectNode) capabilities.path("sessionCapabilities")).putObject("close");
        ObjectNode value = json.createObjectNode().put("protocolVersion", 1);
        value.set("agentCapabilities", capabilities);
        value.putObject("agentInfo").put("name", "java-agent").put("title", "java-agent")
                .put("version", "0.2.0");
        value.putArray("authMethods");
        result(id, value);
    }

    private void newSession(JsonNode id, JsonNode params) throws Exception {
        requireObject(params);
        List<JsonNode> servers = mcpServers(params);
        stopPromptForSessionChange();
        String sessionId = backend.newSession(servers);
        result(id, sessionConfiguration(sessionId));
        availableCommands(sessionId);
    }

    private void loadSession(JsonNode id, JsonNode params, boolean resume) throws Exception {
        requireObject(params);
        String sessionId = requiredText(params, "sessionId");
        List<JsonNode> servers = mcpServers(params);
        stopPromptForSessionChange();
        if (resume) backend.resumeSession(sessionId, servers); else backend.loadSession(sessionId, servers);
        result(id, sessionConfiguration(null));
    }

    private void closeSession(JsonNode id, JsonNode params) throws Exception {
        requireObject(params);
        String sessionId = requiredText(params, "sessionId");
        stopPromptForSessionChange();
        backend.closeSession(sessionId);
        result(id, json.createObjectNode());
    }

    private void listSessions(JsonNode id) throws Exception {
        ArrayNode sessions = json.createArrayNode();
        for (SessionSummary summary : backend.listSessions()) {
            sessions.addObject().put("sessionId", summary.id()).put("cwd", summary.cwd())
                    .put("updatedAt", DateTimeFormatter.ISO_INSTANT.format(summary.updatedAt()));
        }
        result(id, json.createObjectNode().set("sessions", sessions));
    }

    private void setMode(JsonNode id, JsonNode params) throws Exception {
        requireActive();
        String mode = requiredText(params, "modeId");
        if (!mode.equals("code") && !mode.equals("ask")) throw new IllegalArgumentException("Invalid session mode");
        backend.setMode(mode);
        result(id, json.nullNode());
    }

    private void setConfig(JsonNode id, JsonNode params) throws Exception {
        requireActive();
        String config = requiredText(params, "configId");
        String value = requiredText(params, "value");
        if (config.equals("model")) {
            if (value.length() > 200) throw new IllegalArgumentException("Invalid session model");
            backend.setModel(value);
        } else if (config.equals("mode")) {
            if (!value.equals("code") && !value.equals("ask")) throw new IllegalArgumentException("Invalid session mode");
            backend.setMode(value);
        } else {
            throw new IllegalArgumentException("Unknown config option");
        }
        result(id, json.createObjectNode().set("configOptions", configOptions()));
    }

    private void startPrompt(JsonNode id, JsonNode params) throws Exception {
        String sessionId = requireActive();
        requireObject(params);
        if (params.path("sessionId").isTextual() && !params.path("sessionId").asText().equals(sessionId)) {
            throw new IllegalArgumentException("Session is not active");
        }
        String prompt = promptText(params.path("prompt"));
        ActivePrompt active = new ActivePrompt(id, sessionId);
        synchronized (promptLock) {
            if (activePrompt != null) throw new IllegalArgumentException("Prompt already in progress");
            activePrompt = active;
        }
        prompts.submit(() -> runPrompt(active, prompt));
    }

    private void runPrompt(ActivePrompt active, String prompt) {
        active.thread = Thread.currentThread();
        boolean[] streamed = { false };
        try {
            String answer = backend.prompt(prompt, delta -> {
                if (delta == null || delta.isEmpty() || active.cancelled) return;
                streamed[0] = true;
                try { agentChunk(active.sessionId, delta); }
                catch (IOException writeFailure) { throw new PromptWriteFailure(writeFailure); }
            });
            if (active.cancelled) {
                result(active.id, json.createObjectNode().put("stopReason", "cancelled"));
            } else {
                if (!streamed[0] && answer != null && !answer.isBlank()) agentChunk(active.sessionId, answer);
                result(active.id, json.createObjectNode().put("stopReason", "end_turn"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            quietPromptResult(active, "cancelled");
        } catch (Exception failure) {
            if (active.cancelled) quietPromptResult(active, "cancelled");
            else quietError(active.id, -32603, safeMessage(failure));
        } finally {
            synchronized (promptLock) {
                if (activePrompt == active) activePrompt = null;
                promptLock.notifyAll();
            }
        }
    }

    private String promptText(JsonNode blocks) {
        if (!blocks.isArray()) throw new IllegalArgumentException("Missing prompt");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : blocks) {
            String type = block.path("type").asText();
            if (type.equals("image") || type.equals("audio")) {
                throw new IllegalArgumentException(type + " prompt blocks are unsupported");
            }
            if (!type.equals("text") || !block.path("text").isTextual()) {
                throw new IllegalArgumentException("Unsupported prompt block");
            }
            text.append(block.path("text").asText());
            if (text.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Prompt is too large");
        }
        if (text.length() == 0) throw new IllegalArgumentException("Prompt must contain text");
        return text.toString();
    }

    private ObjectNode sessionConfiguration(String sessionId) throws Exception {
        ObjectNode value = json.createObjectNode();
        if (sessionId != null) value.put("sessionId", sessionId);
        value.set("configOptions", configOptions());
        value.putObject("modes").put("currentModeId", backend.currentMode())
                .set("availableModes", modes());
        return value;
    }

    private ArrayNode configOptions() throws Exception {
        ArrayNode values = json.createArrayNode();
        ObjectNode model = values.addObject().put("id", "model").put("name", "Model")
                .put("category", "model").put("type", "select").put("currentValue", backend.currentModel());
        model.putArray("options").addObject().put("value", backend.currentModel()).put("name", backend.currentModel());
        ObjectNode mode = values.addObject().put("id", "mode").put("name", "Session Mode")
                .put("description", "Controls how the agent requests permission")
                .put("category", "mode").put("type", "select").put("currentValue", backend.currentMode());
        mode.set("options", modeOptions());
        return values;
    }

    private ArrayNode modes() {
        ArrayNode values = json.createArrayNode();
        values.addObject().put("id", "code").put("name", "Code")
                .put("description", "Work autonomously with conservative automatic approval");
        values.addObject().put("id", "ask").put("name", "Ask")
                .put("description", "Request approval before sensitive actions");
        return values;
    }

    private ArrayNode modeOptions() {
        ArrayNode values = json.createArrayNode();
        values.addObject().put("value", "code").put("name", "Code")
                .put("description", "Work autonomously with conservative automatic approval")
                .put("permissionMode", "auto");
        values.addObject().put("value", "ask").put("name", "Ask")
                .put("description", "Request approval before sensitive actions")
                .put("permissionMode", "ask");
        return values;
    }

    private void availableCommands(String sessionId) throws IOException {
        ObjectNode update = json.createObjectNode().put("sessionUpdate", "available_commands_update");
        update.putArray("availableCommands");
        sessionUpdate(sessionId, update);
    }

    private void agentChunk(String sessionId, String delta) throws IOException {
        ObjectNode update = json.createObjectNode().put("sessionUpdate", "agent_message_chunk");
        update.putObject("content").put("type", "text").put("text", delta);
        sessionUpdate(sessionId, update);
    }

    private void sessionUpdate(String sessionId, ObjectNode update) throws IOException {
        ObjectNode params = json.createObjectNode().put("sessionId", sessionId).set("update", update);
        ObjectNode message = json.createObjectNode().put("jsonrpc", "2.0").put("method", "session/update")
                .set("params", params);
        write(message);
    }

    private void result(JsonNode id, JsonNode value) throws IOException {
        ObjectNode message = json.createObjectNode().put("jsonrpc", "2.0");
        message.set("id", id == null ? json.nullNode() : id.deepCopy());
        message.set("result", value);
        write(message);
    }

    private void error(JsonNode id, int code, String message) throws IOException {
        ObjectNode value = json.createObjectNode().put("jsonrpc", "2.0");
        value.set("id", id == null ? json.nullNode() : id.deepCopy());
        value.putObject("error").put("code", code).put("message", message);
        write(value);
    }

    private void write(ObjectNode message) throws IOException {
        byte[] encoded = json.writeValueAsBytes(message);
        synchronized (outputLock) {
            output.write(encoded);
            output.write('\n');
            output.flush();
        }
    }

    private String requireActive() throws Exception {
        String active = backend.activeSessionId();
        if (active == null || active.isBlank()) throw new IllegalArgumentException("No active session");
        return active;
    }

    private List<JsonNode> mcpServers(JsonNode params) {
        JsonNode value = params.get("mcpServers");
        if (value == null) return List.of();
        if (!value.isArray()) throw new IllegalArgumentException("mcpServers must be an array");
        List<JsonNode> result = new ArrayList<>();
        value.forEach(server -> result.add(server.deepCopy()));
        return List.copyOf(result);
    }

    private void stopPromptForSessionChange() throws Exception {
        cancelActive();
        synchronized (promptLock) {
            long deadline = System.currentTimeMillis() + 5_000;
            while (activePrompt != null && System.currentTimeMillis() < deadline) promptLock.wait(100);
            if (activePrompt != null) throw new IOException("Prompt did not stop");
        }
    }

    private void cancelActive() {
        ActivePrompt active;
        synchronized (promptLock) {
            active = activePrompt;
            if (active != null) active.cancelled = true;
        }
        if (active != null) {
            try { backend.cancel(); } catch (Exception ignored) { }
            if (active.thread != null) active.thread.interrupt();
        }
    }

    private boolean promptRunning() {
        synchronized (promptLock) { return activePrompt != null; }
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Params must be object");
    }

    private static String requiredText(JsonNode value, String field) {
        requireObject(value);
        if (!value.path(field).isTextual() || value.path(field).asText().isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value.path(field).asText();
    }

    private static boolean validId(JsonNode id) {
        return id == null || id.isNull() || id.isTextual() || id.isIntegralNumber();
    }

    private void quietPromptResult(ActivePrompt active, String reason) {
        try { result(active.id, json.createObjectNode().put("stopReason", reason)); } catch (IOException ignored) { }
    }

    private void quietError(JsonNode id, int code, String message) {
        try { error(id, code, message); } catch (IOException ignored) { }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (error instanceof PromptWriteFailure && error.getCause() instanceof Exception cause) {
            message = cause.getMessage();
        }
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    interface Backend extends AutoCloseable {
        default void initialize() throws Exception { }
        String newSession(List<JsonNode> mcpServers) throws Exception;
        void loadSession(String id, List<JsonNode> mcpServers) throws Exception;
        void resumeSession(String id, List<JsonNode> mcpServers) throws Exception;
        void closeSession(String id) throws Exception;
        List<SessionSummary> listSessions() throws Exception;
        String activeSessionId();
        String currentModel() throws Exception;
        String currentMode() throws Exception;
        void setModel(String value) throws Exception;
        void setMode(String value) throws Exception;
        String prompt(String prompt, Consumer<String> delta) throws Exception;
        void cancel() throws Exception;
        default boolean supportsMcpHttp() { return false; }
        default boolean supportsMcpSse() { return false; }
        @Override void close() throws Exception;
    }

    record SessionSummary(String id, String cwd, Instant updatedAt) { }

    private static final class ActivePrompt {
        final JsonNode id;
        final String sessionId;
        volatile boolean cancelled;
        volatile Thread thread;
        ActivePrompt(JsonNode id, String sessionId) { this.id = id.deepCopy(); this.sessionId = sessionId; }
    }

    private record Frame(byte[] bytes, boolean overflow) { }

    private static final class FrameReader {
        private final BufferedInputStream input;
        FrameReader(InputStream input) { this.input = new BufferedInputStream(input, 64 * 1024); }

        Frame next() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            boolean overflow = false;
            int value;
            while ((value = input.read()) >= 0) {
                if (value == '\n') break;
                if (!overflow) {
                    if (bytes.size() == MAX_FRAME_BYTES) overflow = true;
                    else bytes.write(value);
                }
            }
            if (value < 0) {
                if (overflow) return new Frame(new byte[0], true);
                return null;
            }
            byte[] frame = bytes.toByteArray();
            if (frame.length > 0 && frame[frame.length - 1] == '\r') {
                frame = java.util.Arrays.copyOf(frame, frame.length - 1);
            }
            return new Frame(frame, overflow);
        }
    }

    private static final class PromptWriteFailure extends RuntimeException {
        PromptWriteFailure(IOException cause) { super(cause); }
    }
}
