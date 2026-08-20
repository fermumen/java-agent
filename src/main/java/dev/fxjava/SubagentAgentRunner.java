package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Reconfigurable Responses-backed child while retaining its conversation. */
final class SubagentAgentRunner implements SubagentManager.ChildRunner {
    private final ObjectMapper json;
    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final Path workspace;
    private final int maxSteps;
    private final Path sessionRoot;
    private final AtomicReference<List<Tool>> tools;
    private final ApprovalPolicy parentApproval;
    private final PrintStream progress;
    private final Agent.ParentContext parentContext;
    private SubagentManager.ChildConfiguration configuration;
    private Agent agent;

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace, int maxSteps,
                        Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration) throws Exception {
        this(json, apiKey, baseUrl, defaultModel, workspace, maxSteps, sessionRoot, tools,
                parentApproval, progress, configuration, null);
    }

    SubagentAgentRunner(ObjectMapper json, String apiKey, String baseUrl, String defaultModel, Path workspace, int maxSteps,
                        Path sessionRoot, AtomicReference<List<Tool>> tools,
                        ApprovalPolicy parentApproval, PrintStream progress,
                        SubagentManager.ChildConfiguration configuration,
                        Agent.ParentContext parentContext) throws Exception {
        this.json = json;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.workspace = workspace;
        this.maxSteps = maxSteps;
        this.sessionRoot = sessionRoot;
        this.tools = tools;
        this.parentApproval = parentApproval;
        this.progress = progress;
        this.parentContext = parentContext;
        this.configuration = configuration;
        this.agent = build(configuration);
    }

    @Override public synchronized String prompt(String prompt) throws Exception { return agent.prompt(prompt); }

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
        String model = child.model() == null ? defaultModel : child.model();
        AgentConfig config = new AgentConfig(apiKey, baseUrl, model, workspace, maxSteps, child.permissionMode());
        ToolResultStore results = new ToolResultStore(sessionRoot);
        results.setSession(child.id());
        List<Tool> childTools = new java.util.ArrayList<>();
        for (Tool tool : tools.get()) {
            if (tool.name().equals("read_tool_result")) childTools.add(new ReadToolResultTool(results));
            else if (tool instanceof SubagentTool) childTools.add(((SubagentTool) tool).scoped(child.id()));
            else childTools.add(tool);
        }
        Agent built = new Agent(json, new OpenAiResponsesClient(json, config), childTools,
                approval(child.permissionMode()), progress, maxSteps, instructionsFor(child, null), results,
                parentContext);
        built.setToolResultSession(child.id());
        return built;
    }

    private String instructionsFor(SubagentManager.ChildConfiguration child, String prior) throws Exception {
        AgentConfig config = new AgentConfig(apiKey, baseUrl,
                child.model() == null ? defaultModel : child.model(), workspace, maxSteps, child.permissionMode());
        String identity = "\nSubagent identity: " + child.id() + " (" + child.name() + ").\n";
        if (prior != null) {
            int marker = prior.indexOf("\nSubagent identity:");
            return (marker >= 0 ? prior.substring(0, marker) : prior) + identity;
        }
        return Agent.defaultSystemPrompt(config) + SkillTool.catalog(workspace, sessionRoot) + identity;
    }

    private ApprovalPolicy approval(PermissionMode mode) {
        if (mode == PermissionMode.YOLO) return (tool, arguments) -> true;
        if (mode == PermissionMode.ASK) return parentApproval;
        return (tool, arguments) -> {
            boolean allowed;
            try { allowed = tool.autoApprove(arguments); }
            catch (Exception invalid) { allowed = false; }
            progress.println("[subagent-auto-" + (allowed ? "approved] " : "denied] ") + tool.preview(arguments));
            return allowed;
        };
    }
}
