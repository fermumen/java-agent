package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Reconfigurable Responses-backed child while retaining its conversation. */
final class SubagentAgentRunner implements SubagentManager.ChildRunner {
    private final ObjectMapper json;
    private final String apiKey;
    private final String baseUrl;
    private final String fallbackModel;
    private final Supplier<ModelSelection> defaultSelection;
    private final Path workspace;
    private final int maxSteps;
    private final Path sessionRoot;
    private final AtomicReference<List<Tool>> tools;
    private final ApprovalPolicy parentAuthority;
    private final PrintStream progress;
    private final Agent.ParentContext parentContext;
    private final ContextBudget contextBudget;
    private final Supplier<PermissionMode> parentPermission;
    private SubagentManager.ChildConfiguration configuration;
    private Agent agent;

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace, int maxSteps,
                        Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration) throws Exception {
        this(json, apiKey, baseUrl, defaultModel, workspace, maxSteps, sessionRoot, tools,
                parentApproval, progress, configuration, null, new ContextBudget(),
                () -> new ModelSelection(defaultModel, null));
    }

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace, int maxSteps,
                        Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration,
                        Agent.ParentContext parentContext) throws Exception {
        this(json, apiKey, baseUrl, defaultModel, workspace, maxSteps, sessionRoot, tools,
                parentApproval, progress, configuration, parentContext, new ContextBudget(),
                () -> new ModelSelection(defaultModel, null));
    }

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace, int maxSteps,
                        Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration,
                        Agent.ParentContext parentContext, ContextBudget contextBudget) throws Exception {
        this(json, apiKey, baseUrl, defaultModel, workspace, maxSteps, sessionRoot, tools,
                parentApproval, progress, configuration, parentContext, contextBudget,
                () -> new ModelSelection(defaultModel, null));
    }

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace,
                        int maxSteps, Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration,
                        Agent.ParentContext parentContext, ContextBudget contextBudget,
                        Supplier<ModelSelection> defaultSelection) throws Exception {
        this(json, apiKey, baseUrl, defaultModel, workspace, maxSteps, sessionRoot, tools,
                parentApproval, progress, configuration, parentContext, contextBudget,
                defaultSelection, () -> PermissionMode.YOLO);
    }

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace,
                        int maxSteps, Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration,
                        Agent.ParentContext parentContext, ContextBudget contextBudget,
                        Supplier<ModelSelection> defaultSelection,
                        Supplier<PermissionMode> parentPermission) throws Exception {
        this.json = json;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.fallbackModel = defaultModel;
        this.defaultSelection = defaultSelection == null
                ? () -> new ModelSelection(defaultModel, null) : defaultSelection;
        this.workspace = workspace;
        this.maxSteps = maxSteps;
        this.sessionRoot = sessionRoot;
        this.tools = tools;
        this.parentAuthority = parentApproval;
        this.progress = progress;
        this.parentContext = parentContext;
        this.contextBudget = contextBudget;
        this.parentPermission = parentPermission == null ? () -> PermissionMode.YOLO : parentPermission;
        this.configuration = configuration;
        this.agent = build(configuration);
    }

    @Override
    public synchronized String prompt(String prompt) throws Exception {
        agent.setModelSelection(selectionFor(configuration));
        return agent.prompt(prompt);
    }

    @Override
    public synchronized void configure(SubagentManager.ChildConfiguration replacement) throws Exception {
        ArrayNode history = agent.snapshotInput();
        String instructions = agent.instructions();
        Agent rebuilt = build(replacement);
        rebuilt.restoreConversation(history, instructionsFor(replacement, instructions));
        configuration = replacement;
        agent = rebuilt;
    }

    @Override
    public synchronized List<Agent.ToolCallRecord> toolActivity() {
        return agent.lastToolCalls();
    }

    @Override
    public synchronized ObjectNode snapshot() {
        ObjectNode result = json.createObjectNode().put("instructions", agent.instructions());
        result.set("input", agent.snapshotInput());
        return result;
    }

    @Override
    public synchronized void restore(ObjectNode snapshot) {
        if (snapshot != null && snapshot.path("input").isArray() && snapshot.path("instructions").isTextual()) {
            agent.restoreConversation((ArrayNode) snapshot.path("input"), snapshot.path("instructions").asText());
        }
    }

    private Agent build(SubagentManager.ChildConfiguration child) throws Exception {
        AgentConfig config = configFor(child);
        ToolResultStore results = new ToolResultStore(sessionRoot);
        results.setSession(child.id());
        List<Tool> childTools = new java.util.ArrayList<>();
        for (Tool tool : tools.get()) {
            if (tool.name().equals("read_tool_result")) childTools.add(new ReadToolResultTool(results));
            else if (tool instanceof SubagentTool) childTools.add(((SubagentTool) tool).scoped(child.id()));
            else childTools.add(tool);
        }
        Agent built = new Agent(json, new OpenAiResponsesClient(json, config), childTools,
                approval(child.permissionMode(), parentAuthority, progress, parentPermission), progress, maxSteps,
                instructionsFor(child, null), results,
                parentContext, contextBudget);
        built.setToolResultSession(child.id());
        return built;
    }

    private String instructionsFor(SubagentManager.ChildConfiguration child, String prior) throws Exception {
        AgentConfig config = configFor(child);
        String identity = "\nSubagent identity: " + child.id() + " (" + child.name() + ").\n";
        if (prior != null) {
            int marker = prior.indexOf("\nSubagent identity:");
            return (marker >= 0 ? prior.substring(0, marker) : prior) + identity;
        }
        return Agent.defaultSystemPrompt(config) + SkillTool.catalog(workspace, sessionRoot) + identity;
    }

    private AgentConfig configFor(SubagentManager.ChildConfiguration child) {
        ModelSelection selection = selectionFor(child);
        return new AgentConfig(apiKey, baseUrl, selection.model(), workspace, maxSteps, child.permissionMode(),
                contextBudget.requestTokenBudget(), contextBudget.triggerPercent(), contextBudget.imageTokenReserve(),
                selection.reasoningEffort());
    }

    private ModelSelection selectionFor(SubagentManager.ChildConfiguration child) {
        ModelSelection inherited = defaultSelection.get();
        if (inherited == null) inherited = new ModelSelection(fallbackModel, null);
        String model = child.model() == null || child.model().isBlank() ? inherited.model() : child.model();
        String configuredEffort = child.effort();
        String effort = configuredEffort == null || configuredEffort.isBlank()
                ? inherited.reasoningEffort()
                : configuredEffort.equalsIgnoreCase("default") ? null : configuredEffort;
        return new ModelSelection(model, effort);
    }

    static ApprovalPolicy approval(PermissionMode mode, ApprovalPolicy parentAuthority, PrintStream progress) {
        return approval(mode, parentAuthority, progress, () -> PermissionMode.YOLO);
    }

    static ApprovalPolicy approval(PermissionMode mode, ApprovalPolicy parentAuthority, PrintStream progress,
                                   Supplier<PermissionMode> parentPermission) {
        return new ApprovalPolicy() {
            private PermissionMode effectiveMode() {
                PermissionMode parent = parentPermission == null ? PermissionMode.ASK : parentPermission.get();
                if (parent == null) parent = PermissionMode.ASK;
                return mode.ordinal() > parent.ordinal() ? parent : mode;
            }

            @Override
            public boolean preflightDeny(Tool tool, com.fasterxml.jackson.databind.JsonNode arguments) {
                if (effectiveMode() == PermissionMode.YOLO) return false;
                return parentAuthority.preflightDeny(tool, arguments);
            }

            @Override
            public boolean approve(Tool tool, com.fasterxml.jackson.databind.JsonNode arguments) {
                PermissionMode active = effectiveMode();
                if (active == PermissionMode.YOLO) return true;
                if (preflightDeny(tool, arguments)) return false;
                if (active == PermissionMode.ASK) return parentAuthority.approve(tool, arguments);
                boolean allowed;
                try { allowed = tool.autoApprove(arguments); }
                catch (Exception invalid) { allowed = false; }
                progress.println("[subagent-auto-" + (allowed ? "approved] " : "denied] ")
                        + tool.preview(arguments));
                return allowed;
            }
        };
    }
}
