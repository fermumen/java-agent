package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Native Responses and durable-session implementation behind the ACP adapter. */
final class AcpAgentBackend implements AcpServer.Backend {
    private final ObjectMapper json;
    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final Path workspace;
    private final int maxSteps;
    private final PermissionMode permissionCeiling;
    private final Path sessionRoot;
    private final ToolResultStore resultStore;
    private final McpRuntime mcp;
    private final SubagentManager subagents;
    private final List<Tool> tools;
    private final AtomicReference<String> activeModel = new AtomicReference<>();
    private final PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
    private SessionStore store;
    private SessionRuntime session;
    private String model;
    private String mode = "ask";

    AcpAgentBackend(ObjectMapper json, String apiKey, String baseUrl, String defaultModel,
                    Path workspace, int maxSteps, PermissionMode permissionCeiling,
                    Path sessionRoot, Path mcpConfig, boolean webSearch) throws Exception {
        this.json = json;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.workspace = workspace.toRealPath();
        this.maxSteps = maxSteps;
        this.permissionCeiling = permissionCeiling;
        this.sessionRoot = sessionRoot.toAbsolutePath().normalize();
        this.model = defaultModel;
        this.resultStore = new ToolResultStore(this.sessionRoot);
        this.mcp = McpRuntime.load(json, mcpConfig);

        List<Tool> catalog = new ArrayList<>(WorkspaceTools.create(this.workspace, this.sessionRoot));
        catalog.add(new InstallSkillTool(this.workspace, this.sessionRoot));
        catalog.add(new WebFetchTool());
        if (webSearch) catalog.add(new HostedWebSearchTool());
        catalog.add(new ReadToolResultTool(resultStore));
        catalog.addAll(mcp.tools());
        AtomicReference<List<Tool>> childTools = new AtomicReference<>();
        activeModel.set(defaultModel);
        this.subagents = new SubagentManager(json, child -> new SubagentAgentRunner(json, apiKey, baseUrl,
                activeModel.get(), this.workspace, maxSteps, this.sessionRoot, childTools,
                approval(effectivePermission()), quiet, child), permissionCeiling, this.sessionRoot);
        catalog.add(new SubagentTool(subagents));
        this.tools = List.copyOf(catalog);
        childTools.set(this.tools);
    }

    @Override public synchronized void initialize() throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("An OpenAI API key is required");
        }
        subagents.restore();
    }

    @Override
    public synchronized String newSession(List<JsonNode> mcpServers) throws Exception {
        rejectSuppliedMcp(mcpServers);
        model = defaultModel;
        activeModel.set(model);
        mode = "ask";
        Agent agent = buildAgent();
        session = SessionRuntime.start(agent, store(), workspace, model, systemPrompt(), null);
        return session.id();
    }

    @Override
    public synchronized void loadSession(String id, List<JsonNode> mcpServers) throws Exception {
        rejectSuppliedMcp(mcpServers);
        SessionStore.Snapshot snapshot = store().load(id);
        requireWorkspace(snapshot);
        model = snapshot.model();
        activeModel.set(model);
        mode = "ask";
        Agent agent = buildAgent();
        session = SessionRuntime.start(agent, store, workspace, model, systemPrompt(), id);
        session.reconfigure(buildAgent(), model, systemPrompt());
    }

    @Override public synchronized void resumeSession(String id, List<JsonNode> mcpServers) throws Exception {
        loadSession(id, mcpServers);
    }

    @Override public synchronized void closeSession(String id) {
        if (session == null || !id.equals(session.id())) throw new IllegalArgumentException("Session is not active");
        session = null;
        resultStore.setSession(null);
    }

    @Override
    public synchronized List<AcpServer.SessionSummary> listSessions() throws Exception {
        if (!Files.exists(sessionRoot)) return List.of();
        List<AcpServer.SessionSummary> values = new ArrayList<>();
        for (SessionStore.Snapshot snapshot : SessionStore.inspect(json, sessionRoot).list(workspace, 1_000)) {
            values.add(new AcpServer.SessionSummary(snapshot.id(), snapshot.workspace(),
                    Instant.ofEpochMilli(snapshot.updatedAt())));
        }
        return List.copyOf(values);
    }

    @Override public synchronized String activeSessionId() { return session == null ? null : session.id(); }
    @Override public synchronized String currentModel() { return model; }
    @Override public synchronized String currentMode() { return mode; }

    @Override
    public synchronized void setModel(String value) throws Exception {
        requireActive();
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("Invalid session model");
        }
        PermissionMode permission = effectivePermission(mode);
        Agent replacement = buildAgent(value, permission);
        String instructions = systemPrompt(value, permission);
        session.reconfigure(replacement, value, instructions);
        model = value;
        activeModel.set(value);
    }

    @Override
    public synchronized void setMode(String value) throws Exception {
        requireActive();
        if (!value.equals("ask") && !value.equals("code")) throw new IllegalArgumentException("Invalid session mode");
        PermissionMode permission = effectivePermission(value);
        Agent replacement = buildAgent(model, permission);
        String instructions = systemPrompt(model, permission);
        session.reconfigure(replacement, model, instructions);
        mode = value;
    }

    @Override
    public String prompt(String prompt, Consumer<String> delta) throws Exception {
        SessionRuntime active;
        synchronized (this) {
            requireActive();
            active = session;
        }
        return active.prompt(prompt, delta);
    }

    @Override public void cancel() {
        // AcpServer interrupts the prompt thread; HttpClient and process waits propagate interruption.
    }

    @Override
    public void close() {
        subagents.close();
        mcp.close();
        quiet.close();
    }

    private Agent buildAgent() throws Exception {
        return buildAgent(model, effectivePermission(mode));
    }

    private Agent buildAgent(String selectedModel, PermissionMode permission) throws Exception {
        AgentConfig config = new AgentConfig(apiKey, baseUrl, selectedModel, workspace, maxSteps, permission);
        return new Agent(json, new OpenAiResponsesClient(json, config), tools, approval(permission), quiet,
                maxSteps, systemPrompt(selectedModel, permission), resultStore);
    }

    private String systemPrompt() throws Exception {
        return systemPrompt(model, effectivePermission(mode));
    }

    private String systemPrompt(String selectedModel, PermissionMode permission) throws Exception {
        AgentConfig config = new AgentConfig(apiKey, baseUrl, selectedModel, workspace, maxSteps, permission);
        return Agent.defaultSystemPrompt(config) + SkillTool.catalog(workspace, sessionRoot);
    }

    private PermissionMode effectivePermission() { return effectivePermission(mode); }

    private PermissionMode effectivePermission(String selectedMode) {
        if (permissionCeiling == PermissionMode.YOLO) return PermissionMode.YOLO;
        PermissionMode requested = selectedMode.equals("code") ? PermissionMode.AUTO : PermissionMode.ASK;
        return requested.ordinal() > permissionCeiling.ordinal() ? permissionCeiling : requested;
    }

    private ApprovalPolicy approval(PermissionMode permission) {
        if (permission == PermissionMode.YOLO) return (tool, arguments) -> true;
        if (permission == PermissionMode.AUTO) return (tool, arguments) -> {
            try { return tool.autoApprove(arguments); } catch (Exception invalid) { return false; }
        };
        return (tool, arguments) -> false;
    }

    private SessionStore store() throws Exception {
        if (store == null) store = new SessionStore(json, sessionRoot);
        return store;
    }

    private void requireActive() {
        if (session == null) throw new IllegalArgumentException("No active session");
    }

    private void requireWorkspace(SessionStore.Snapshot snapshot) throws Exception {
        if (!snapshot.workspace().equals(workspace.toString())) {
            throw new IllegalArgumentException("Session belongs to a different workspace");
        }
    }

    private static void rejectSuppliedMcp(List<JsonNode> servers) {
        if (!servers.isEmpty()) throw new IllegalArgumentException("ACP-supplied MCP servers are not implemented");
    }
}
