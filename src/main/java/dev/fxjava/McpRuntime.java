package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Compact bounded MCP stdio and Streamable HTTP client. */
final class McpRuntime implements AutoCloseable {
    private static final int MAX_SERVERS = 32;
    private static final int MAX_PAGES = 256;
    private static final int MAX_TOOLS = 4096;
    private static final int MAX_LINE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 200 * 1024;
    private final ObjectMapper json;
    private final List<Server> servers;
    private final List<Tool> tools;

    private McpRuntime(ObjectMapper json, List<Server> servers, List<Tool> tools) {
        this.json = json;
        this.servers = List.copyOf(servers);
        List<Tool> catalog = new ArrayList<>();
        if (!servers.isEmpty()) catalog.addAll(McpMetaTools.create(this));
        catalog.addAll(tools);
        this.tools = List.copyOf(catalog);
    }

    static McpRuntime load(ObjectMapper json, Path configPath) throws IOException {
        if (!Files.exists(configPath)) return new McpRuntime(json, List.of(), List.of());
        JsonNode root = json.readTree(Files.readString(configPath, StandardCharsets.UTF_8));
        if (!root.isObject()) throw new IOException("MCP config must be a JSON object");
        JsonNode configured = root.path("mcp");
        if (configured.isMissingNode()) return new McpRuntime(json, List.of(), List.of());
        if (!configured.isObject() || configured.size() > MAX_SERVERS) {
            throw new IOException("MCP config must contain at most " + MAX_SERVERS + " servers");
        }
        List<Server> servers = new ArrayList<>();
        List<Tool> tools = new ArrayList<>();
        Set<String> publicNames = new HashSet<>();
        try {
            for (var entry : configured.properties()) {
                ServerConfig config = parseConfig(entry.getKey(), entry.getValue());
                if (!config.enabled()) continue;
                Server server = new Server(json, config);
                try {
                    server.start();
                    List<Tool> discovered = new ArrayList<>();
                    Set<String> discoveredNames = new HashSet<>();
                    for (RemoteTool remote : server.discoverTools()) {
                        String publicName = publicName(config.name(), remote.name());
                        if (publicNames.contains(publicName) || !discoveredNames.add(publicName)) {
                            throw new IOException("Duplicate MCP tool identity: " + publicName);
                        }
                        discovered.add(new McpTool(server, publicName, remote));
                    }
                    publicNames.addAll(discoveredNames);
                    tools.addAll(discovered);
                    servers.add(server);
                } catch (Exception startupFailure) {
                    server.close();
                    if (config.required()) {
                        if (startupFailure instanceof IOException io) throw io;
                        throw new IOException("Required MCP server failed: " + config.name(), startupFailure);
                    }
                }
            }
            return new McpRuntime(json, servers, tools);
        } catch (Exception failure) {
            for (Server server : servers) server.close();
            if (failure instanceof IOException io) throw io;
            throw new IOException("Could not start MCP runtime", failure);
        }
    }

    List<Tool> tools() {
        return tools;
    }

    record McpToolInfo(String publicName, String server, String remoteName, String description,
                       ObjectNode schema, boolean readOnly) { }

    ObjectMapper json() { return json; }

    List<McpToolInfo> toolCatalog() {
        List<McpToolInfo> result = new ArrayList<>();
        for (Tool tool : tools) {
            if (tool instanceof McpTool remote) {
                result.add(new McpToolInfo(remote.publicName, remote.server.config.name(),
                        remote.remote.name(), remote.remote.description(),
                        remote.remote.inputSchema().deepCopy(), remote.remote.readOnly()));
            }
        }
        return List.copyOf(result);
    }

    void selectTool(String name) throws IOException {
        for (Tool tool : tools) {
            if (tool instanceof McpTool remote && remote.publicName.equals(name)) {
                remote.selected = true;
                return;
            }
        }
        throw new IOException("Unknown MCP tool: " + name);
    }

    JsonNode featureRequest(String serverName, String method, ObjectNode params) throws IOException {
        Server server = server(serverName);
        return server.request(method, params, server.config.operationTimeoutMs());
    }

    JsonNode pagedFeature(String serverName, String method, String arrayField) throws IOException {
        Server server = server(serverName);
        ObjectNode combined = json.createObjectNode();
        ArrayNode values = combined.putArray(arrayField);
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = server.request(method, params, server.config.operationTimeoutMs());
            JsonNode items = result.path(arrayField);
            if (!items.isArray()) throw new IOException("MCP " + method + " omitted " + arrayField);
            for (JsonNode item : items) {
                if (values.size() >= MAX_TOOLS) throw new IOException("MCP feature catalog exceeds item limit");
                values.add(item.deepCopy());
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull()) return combined;
            if (!next.isTextual() || !cursors.add(next.asText())) throw new IOException("MCP feature repeated or invalid cursor");
            cursor = next.asText();
        }
        throw new IOException("MCP feature exceeded page limit");
    }

    String bounded(JsonNode result) throws IOException {
        McpValidation.json(result);
        byte[] bytes = json.writeValueAsBytes(result);
        if (bytes.length > MAX_RESULT_BYTES) throw new IOException("MCP result exceeds 200 KiB");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Server server(String name) throws IOException {
        for (Server server : servers) if (server.config.name().equals(name)) return server;
        throw new IOException("Unknown MCP server: " + name);
    }

    @Override
    public void close() {
        for (Server server : servers) server.close();
    }

    private static ServerConfig parseConfig(String name, JsonNode node) throws IOException {
        if (!name.matches("[A-Za-z0-9_-]{1,64}") || !node.isObject()) {
            throw new IOException("Invalid MCP server entry: " + name);
        }
        boolean enabled = booleanField(node, "enabled", true);
        boolean required = booleanField(node, "required", false);
        String type = node.path("type").asText("stdio");
        if (!type.equals("stdio") && !type.equals("local") && !type.equals("http")) {
            throw new IOException("MCP server " + name + " uses unsupported transport: " + type);
        }
        List<String> command = new ArrayList<>();
        String url = null;
        if (type.equals("http")) {
            url = validateRemoteUrl(name, node.path("url").asText());
        } else {
            JsonNode commandNode = node.get("command");
            if (commandNode == null) throw new IOException("MCP server " + name + " has no command");
            if (commandNode.isTextual()) {
                command.add(nonBlank(commandNode.asText(), "command"));
                JsonNode args = node.path("args");
                if (!args.isMissingNode()) appendStrings(args, command, "args");
            } else {
                appendStrings(commandNode, command, "command");
            }
            if (command.isEmpty() || command.size() > 128) throw new IOException("Invalid MCP command for " + name);
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        JsonNode env = node.has("environment") ? node.get("environment") : node.get("env");
        if (env != null) {
            if (!env.isObject() || env.size() > 128) throw new IOException("Invalid MCP environment for " + name);
            for (var entry : env.properties()) {
                if (!entry.getValue().isTextual()) throw new IOException("MCP environment values must be strings");
                environment.put(entry.getKey(), entry.getValue().asText());
            }
        }
        Map<String, String> headers = parseHeaders(name, node.path("headers"));
        int startup = intField(node, "startup_timeout_ms", 10_000, 1, 120_000);
        int operation = intField(node, "operation_timeout_ms", 30_000, 1, 600_000);
        return new ServerConfig(name, type, List.copyOf(command), url, headers, Map.copyOf(environment),
                enabled, required, startup, operation);
    }

    private static String validateRemoteUrl(String server, String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("MCP server " + server + " has no URL");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid MCP URL for " + server, invalid);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1"));
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback))) {
            throw new IOException("MCP HTTP URL must use HTTPS or loopback HTTP: " + value);
        }
        return uri.toString();
    }

    private static Map<String, String> parseHeaders(String server, JsonNode value) throws IOException {
        if (value.isMissingNode() || value.isNull()) return Map.of();
        if (!value.isObject() || value.size() > 64) throw new IOException("Invalid MCP headers for " + server);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (var entry : value.properties()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!name.matches("[!#0&*+.^_`|~0-9A-Za-z-]+") || !entry.getValue().isTextual()
                    || entry.getValue().asText().contains("\r") || entry.getValue().asText().contains("\n")
                    || lower.equals("authorization") || lower.equals("content-type") || lower.equals("accept")
                    || lower.startsWith("mcp-")) {
                throw new IOException("Unsafe MCP header in " + server + ": " + name);
            }
            headers.put(name, entry.getValue().asText());
        }
        return Map.copyOf(headers);
    }

    private static void appendStrings(JsonNode array, List<String> target, String field) throws IOException {
        if (!array.isArray()) throw new IOException("MCP " + field + " must be an array of strings");
        for (JsonNode value : array) {
            if (!value.isTextual()) throw new IOException("MCP " + field + " must contain only strings");
            target.add(nonBlank(value.asText(), field));
        }
    }

    private static String nonBlank(String value, String field) throws IOException {
        if (value.isBlank()) throw new IOException("MCP " + field + " must not be blank");
        return value;
    }

    private static boolean booleanField(JsonNode node, String field, boolean fallback) throws IOException {
        JsonNode value = node.get(field);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new IOException("MCP " + field + " must be boolean");
        return value.asBoolean();
    }

    private static int intField(JsonNode node, String field, int fallback, int minimum, int maximum)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null) return fallback;
        if (!value.canConvertToInt() || value.asInt() < minimum || value.asInt() > maximum) {
            throw new IOException("MCP " + field + " is out of range");
        }
        return value.asInt();
    }

    private static String publicName(String server, String tool) throws IOException {
        if (tool.isBlank() || tool.length() > 256) throw new IOException("Invalid MCP tool name");
        String safe = tool.replaceAll("[^A-Za-z0-9_-]", "_");
        String value = "mcp__" + server + "__" + safe;
        if (value.length() > 64) value = value.substring(0, 55) + "_" + Integer.toHexString(tool.hashCode());
        return value;
    }

    private record ServerConfig(String name, String type, List<String> command, String url, Map<String, String> headers,
                                Map<String, String> environment,
                                boolean enabled, boolean required, int startupTimeoutMs, int operationTimeoutMs) { }

    private record RemoteTool(String name, String description, ObjectNode inputSchema, boolean readOnly) { }

    private static final class McpTool implements Tool {
        private final Server server;
        private final String publicName;
        private final RemoteTool remote;
        private volatile boolean selected;

        McpTool(Server server, String publicName, RemoteTool remote) {
            this.server = server;
            this.publicName = publicName;
            this.remote = remote;
        }

        @Override public String name() { return publicName; }
        @Override public boolean advertised() { return selected; }
        @Override public String description() {
            return "MCP " + server.config.name() + "/" + remote.name() + ": " + remote.description();
        }
        @Override public ObjectNode parameters() { return remote.inputSchema().deepCopy(); }
        @Override public boolean requiresApproval() { return !remote.readOnly(); }
        @Override public String preview(JsonNode arguments) {
            return "MCP " + server.config.name() + "/" + remote.name();
        }
        @Override public String execute(JsonNode arguments) throws Exception {
            ObjectNode params = server.json.createObjectNode();
            params.put("name", remote.name());
            params.set("arguments", arguments.deepCopy());
            JsonNode result = server.request("tools/call", params, server.config.operationTimeoutMs());
            McpValidation.toolResult(result);
            byte[] encoded = server.json.writeValueAsBytes(result);
            if (encoded.length > MAX_RESULT_BYTES) throw new IOException("MCP tool result exceeds 200 KiB");
            return new String(encoded, StandardCharsets.UTF_8);
        }
    }

    private static final class Server implements AutoCloseable {
        private final ObjectMapper json;
        private final ServerConfig config;
        private final LinkedBlockingQueue<String> inbound = new LinkedBlockingQueue<>(1024);
        private final AtomicBoolean closed = new AtomicBoolean();
        private Process process;
        private BufferedWriter writer;
        private HttpClient http;
        private URI endpoint;
        private String sessionId;
        private String protocolVersion = "2025-06-18";
        private boolean handshakeComplete;
        private long nextId = 1;

        Server(ObjectMapper json, ServerConfig config) {
            this.json = json;
            this.config = config;
        }

        void start() throws IOException {
            if (config.type().equals("http")) {
                endpoint = URI.create(config.url());
                http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.startupTimeoutMs()))
                        .followRedirects(HttpClient.Redirect.NEVER).build();
            } else {
                ProcessBuilder builder = new ProcessBuilder(config.command());
                builder.environment().remove("OPENAI_API_KEY");
                builder.environment().remove("JAVA_AGENT_API_KEY");
                builder.environment().putAll(config.environment());
                process = builder.start();
                writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                startDaemon("mcp-" + config.name() + "-stdout", () -> readStdout(process));
                startDaemon("mcp-" + config.name() + "-stderr", () -> drain(process));
            }
            handshake();
        }

        private void handshake() throws IOException {
            sessionId = null;
            protocolVersion = "2025-06-18";
            handshakeComplete = false;
            ObjectNode params = json.createObjectNode();
            params.put("protocolVersion", "2025-06-18");
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", "java-agent").put("version", "0.2.0");
            JsonNode initialized = request("initialize", params, config.startupTimeoutMs());
            if (!initialized.isObject() || !initialized.path("protocolVersion").isTextual()) {
                throw new IOException("MCP initialize returned an invalid result for " + config.name());
            }
            protocolVersion = initialized.path("protocolVersion").asText();
            handshakeComplete = true;
            notify("notifications/initialized", json.createObjectNode());
        }

        List<RemoteTool> discoverTools() throws IOException {
            ArrayList<RemoteTool> result = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ObjectNode params = json.createObjectNode();
                if (cursor != null) params.put("cursor", cursor);
                JsonNode listed = request("tools/list", params, config.operationTimeoutMs());
                JsonNode tools = listed.path("tools");
                if (!tools.isArray()) throw new IOException("MCP tools/list omitted tools for " + config.name());
                for (JsonNode item : tools) {
                    String name = item.path("name").asText();
                    if (name.isBlank() || !names.add(name)) throw new IOException("Duplicate or invalid MCP tool: " + name);
                    JsonNode schema = item.path("inputSchema");
                    if (!schema.isObject()) throw new IOException("MCP tool has invalid inputSchema: " + name);
                    ObjectNode copied = ((ObjectNode) schema).deepCopy();
                    McpValidation.schema(copied);
                    if (!copied.has("type")) copied.put("type", "object");
                    if (!copied.path("type").asText().equals("object")) {
                        throw new IOException("MCP tool inputSchema must describe an object: " + name);
                    }
                    boolean readOnly = item.path("annotations").path("readOnlyHint").asBoolean(false);
                    result.add(new RemoteTool(name, item.path("description").asText(""), copied, readOnly));
                    if (result.size() > MAX_TOOLS) throw new IOException("MCP catalog exceeds " + MAX_TOOLS + " tools");
                }
                JsonNode next = listed.get("nextCursor");
                if (next == null || next.isNull()) return result.stream()
                        .sorted((a, b) -> a.name().compareTo(b.name())).toList();
                if (!next.isTextual() || !cursors.add(next.asText())) {
                    throw new IOException("MCP tools/list repeated or invalid cursor");
                }
                cursor = next.asText();
            }
            throw new IOException("MCP tools/list exceeded " + MAX_PAGES + " pages");
        }

        synchronized JsonNode request(String method, ObjectNode params, int timeoutMs) throws IOException {
            long id = nextId++;
            ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            request.set("params", params);
            if (http != null) {
                try {
                    return requestHttp(request, id, method, timeoutMs);
                } catch (McpSessionExpired expired) {
                    if (method.equals("initialize")) throw expired;
                    handshake();
                    throw new IOException("MCP session was reinitialized; ambiguous request was not replayed: "
                            + config.name() + "/" + method, expired);
                }
            }
            send(request);
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("MCP request timed out: " + config.name() + "/" + method);
                String line;
                try {
                    line = inbound.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("MCP request interrupted", interrupted);
                }
                if (line == null) throw new IOException("MCP request timed out: " + config.name() + "/" + method);
                if (line.equals("<eof>")) throw new IOException("MCP server exited: " + config.name());
                JsonNode envelope = json.readTree(line);
                if (!envelope.isObject() || !envelope.path("jsonrpc").asText().equals("2.0")) {
                    throw new IOException("Invalid MCP JSON-RPC envelope from " + config.name());
                }
                if (envelope.has("method") && envelope.has("id")) {
                    respondUnsupported(envelope.get("id"));
                    continue;
                }
                if (!envelope.has("id")) continue;
                if (!envelope.path("id").canConvertToLong() || envelope.path("id").asLong() != id) continue;
                boolean hasResult = envelope.has("result");
                boolean hasError = envelope.has("error");
                if (hasResult == hasError) throw new IOException("Invalid MCP response payload from " + config.name());
                if (hasError) throw new IOException("MCP " + method + " failed: "
                        + envelope.path("error").path("message").asText("protocol error"));
                return envelope.get("result");
            }
        }

        private JsonNode requestHttp(ObjectNode request, long id, String method, int timeoutMs) throws IOException {
            HttpResponse<InputStream> response = postHttp(request, timeoutMs, true);
            if (method.equals("initialize")) {
                String candidate = response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (candidate != null) {
                    if (candidate.isEmpty() || candidate.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7e)) {
                        throw new IOException("MCP server returned an invalid session id");
                    }
                    sessionId = candidate;
                }
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(java.util.Locale.ROOT);
            try (InputStream body = response.body()) {
                if (contentType.startsWith("application/json")) {
                    return responsePayload(json.readTree(readBounded(body, MAX_LINE_BYTES)), id, method);
                }
                if (contentType.startsWith("text/event-stream")) {
                    return ssePayload(body, id, method);
                }
            }
            throw new IOException("MCP HTTP response has unsupported content type: " + contentType);
        }

        private HttpResponse<InputStream> postHttp(ObjectNode message, int timeoutMs, boolean responseExpected)
                throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
            if (handshakeComplete) builder.header("MCP-Protocol-Version", protocolVersion);
            for (var header : config.headers().entrySet()) builder.header(header.getKey(), header.getValue());
            builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)));
            HttpResponse<InputStream> response;
            try {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("MCP HTTP request interrupted", interrupted);
            }
            if (response.statusCode() == 404 && sessionId != null) {
                response.body().close();
                throw new McpSessionExpired(config.name());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream body = response.body()) {
                    throw new IOException("MCP HTTP " + response.statusCode() + " from " + config.name() + ": "
                            + abbreviate(new String(readBounded(body, 2000), StandardCharsets.UTF_8), 2000));
                }
            }
            if (responseExpected && response.statusCode() == 202) {
                response.body().close();
                throw new IOException("MCP request returned 202 without a response");
            }
            return response;
        }

        private JsonNode ssePayload(InputStream body, long id, String method) throws IOException {
            InputStream input = body;
            StringBuilder data = new StringBuilder();
            for (String line; (line = readSseLine(input)) != null;) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        JsonNode envelope = json.readTree(data.toString());
                        if (envelope.path("id").canConvertToLong() && envelope.path("id").asLong() == id) {
                            return responsePayload(envelope, id, method);
                        }
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append("\n");
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                    if (data.length() > MAX_LINE_BYTES) throw new IOException("MCP SSE event exceeds 2 MiB");
                }
            }
            throw new IOException("MCP SSE stream ended without response id " + id);
        }

        private static byte[] readBounded(InputStream input, int limit) throws IOException {
            byte[] value = input.readNBytes(limit + 1);
            if (value.length > limit) throw new IOException("MCP HTTP response exceeds " + limit + " bytes");
            return value;
        }

        private static String readSseLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int value = input.read();
                if (value < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
                if (value == 10) return line.toString(StandardCharsets.UTF_8);
                if (value == 13) return line.toString(StandardCharsets.UTF_8);
                line.write(value);
                if (line.size() > MAX_LINE_BYTES) throw new IOException("MCP SSE line exceeds 2 MiB");
            }
        }

        private JsonNode responsePayload(JsonNode envelope, long id, String method) throws IOException {
            if (!envelope.isObject() || !envelope.path("jsonrpc").asText().equals("2.0")
                    || !envelope.path("id").canConvertToLong() || envelope.path("id").asLong() != id) {
                throw new IOException("Invalid MCP JSON-RPC response from " + config.name());
            }
            boolean hasResult = envelope.has("result");
            boolean hasError = envelope.has("error");
            if (hasResult == hasError) throw new IOException("Invalid MCP response payload from " + config.name());
            if (hasError) throw new IOException("MCP " + method + " failed: "
                    + envelope.path("error").path("message").asText("protocol error"));
            return envelope.get("result");
        }

        private static final class McpSessionExpired extends IOException {
            McpSessionExpired(String server) {
                super("MCP session expired: " + server);
            }
        }

        private static String abbreviate(String value, int limit) {
            return value.length() <= limit ? value : value.substring(0, limit) + "...";
        }

        private void notify(String method, ObjectNode params) throws IOException {
            ObjectNode notification = json.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            notification.set("params", params);
            if (http != null) postHttp(notification, config.operationTimeoutMs(), false).body().close();
            else send(notification);
        }

        private void respondUnsupported(JsonNode id) throws IOException {
            ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", id.deepCopy());
            response.putObject("error").put("code", -32601).put("message", "Method not found");
            send(response);
        }

        private void send(ObjectNode value) throws IOException {
            if (closed.get()) throw new IOException("MCP server is closed: " + config.name());
            writer.write(json.writeValueAsString(value));
            writer.newLine();
            writer.flush();
        }

        private static void startDaemon(String name, Runnable task) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.start();
        }

        private void readStdout(Process child) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) break;
                    inbound.put(line);
                }
            } catch (Exception ignored) {
                // The waiting request observes EOF below.
            } finally {
                inbound.offer("<eof>");
            }
        }

        private static void drain(Process child) {
            try (var input = child.getErrorStream()) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) { }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (http != null && sessionId != null) {
                try {
                    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(5)).header("Mcp-Session-Id", sessionId)
                            .header("MCP-Protocol-Version", protocolVersion).DELETE();
                    for (var header : config.headers().entrySet()) request.header(header.getKey(), header.getValue());
                    http.send(request.build(), HttpResponse.BodyHandlers.discarding());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) { }
            }
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
