package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    static final String VERSION = "0.2.0";

    private Main() {
    }

    /**
     * Probes raw mode by entering and immediately restoring it; the detail
     * names the backend or the reason, and on Windows which console API
     * binding loads, even when no console is attached.
     */
    private static String terminalCheck(Map<String, String> environment) throws InterruptedException {
        String binding = "";
        if (WindowsConsole.isWindows()) {
            List<String> unbound = new ArrayList<>();
            WindowsConsole.Api api = WindowsConsole.api(unbound::add);
            // With no binding, the probe below already reports why.
            if (api != null) binding = "; console API: " + api.name();
        }
        if ("dumb".equals(environment.get("TERM"))) return "raw mode unavailable: TERM=dumb" + binding;
        List<String> declined = new ArrayList<>();
        RawTerminal probe = RawTerminal.open(declined::add);
        if (probe == null) {
            return "raw mode unavailable: " + (declined.isEmpty() ? "no raw terminal backend" : declined.get(0)) + binding;
        }
        TerminalCapabilities.Size size = probe.size();
        probe.close();
        return "raw mode available (" + (WindowsConsole.isWindows() ? "Windows console" : "stty")
                + ", " + size + ", java " + System.getProperty("java.version") + ")" + binding;
    }

    public static void main(String[] args) {
        WindowsNetworkDefaults.apply(System.getenv(), System.getProperties());
        try {
            int exitCode = run(args, System.getenv(), System.out, System.err);
            if (exitCode != 0) System.exit(exitCode);
        } catch (Exception error) {
            System.err.println("java-agent: " + safeMessage(error));
            System.exit(1);
        }
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream error)
            throws Exception {
        return run(args, environment, System.in, out, error);
    }

    static int run(String[] args, Map<String, String> environment, InputStream standardInput,
                   PrintStream out, PrintStream error) throws Exception {
        return run(args, environment, standardInput, out, error, null);
    }

    static int run(String[] args, Map<String, String> environment, InputStream standardInput,
                   PrintStream out, PrintStream error, ApprovalRouter approvalOverride) throws Exception {
        return run(args, environment, standardInput, out, error, approvalOverride, null);
    }

    /** The terminal override exercises the real entrypoint without native console access in tests. */
    static int run(String[] args, Map<String, String> environment, InputStream standardInput,
                   PrintStream out, PrintStream error, ApprovalRouter approvalOverride,
                   RawTerminal terminalOverride) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            out.print(usage());
            return 0;
        }
        if (options.version) {
            out.println("java-agent " + VERSION);
            return 0;
        }
        if ("acp".equals(options.command)) return runAcp(options, environment, standardInput, out, error);
        if (options.command != null) return runInfoCommand(options, environment, out, error);

        Path sessionRoot = sessionRoot(options, environment);
        UserPreferences preferences = loadPreferences(sessionRoot, error);
        Console console = System.console();
        String apiKey = firstNonBlank(environment.get("OPENAI_API_KEY"),
                environment.get("JAVA_AGENT_API_KEY"), preferences.apiKey());
        String configuredBaseUrl = configuredBaseUrl(options, environment, preferences);
        String onboardedBaseUrl = null;
        if (apiKey == null && console != null && !options.json) {
            ApiKeyOnboarding.Credentials credentials = ApiKeyOnboarding.request(new ApiKeyOnboarding.Prompt() {
                @Override public char[] readPassword(String prompt) {
                    return console.readPassword("%s", prompt);
                }

                @Override public String readLine(String prompt) {
                    return console.readLine("%s", prompt);
                }
            }, preferences, error, configuredBaseUrl == null);
            if (credentials != null) {
                apiKey = credentials.apiKey;
                onboardedBaseUrl = credentials.baseUrl;
            }
        }
        if (apiKey == null) {
            error.println("java-agent: set OPENAI_API_KEY (or JAVA_AGENT_API_KEY), or run interactively without --json to enter one.");
            return 2;
        }
        String baseUrl;
        try {
            baseUrl = BaseUrl.validate(firstNonBlank(configuredBaseUrl, onboardedBaseUrl, BaseUrl.DEFAULT));
        } catch (IllegalArgumentException invalidBaseUrl) {
            error.println("java-agent: " + invalidBaseUrl.getMessage());
            return 2;
        }
        String model = firstNonBlank(options.model, environment.get("OPENAI_MODEL"),
                environment.get("JAVA_AGENT_MODEL"), preferences.model(), "gpt-5.6");
        String reasoningEffort;
        try {
            reasoningEffort = resolveEffort(options.effort, environment, preferences);
        } catch (IllegalArgumentException invalidEffort) {
            error.println("java-agent: " + invalidEffort.getMessage());
            return 2;
        }
        Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
        if (!Files.isDirectory(workspace)) {
            error.println("java-agent: workspace is not a directory: " + workspace);
            return 2;
        }

        if (options.noSave && options.resume != null) {
            error.println("java-agent: --resume cannot be combined with --no-save");
            return 2;
        }

        PermissionMode permissionMode = options.permissionMode != null ? options.permissionMode
                : PermissionMode.parse(firstNonBlank(environment.get("JAVA_AGENT_PERMISSION_MODE"), "ask"));
        ContextBudget contextBudget = contextBudget(environment);
        AgentConfig config = new AgentConfig(apiKey, baseUrl, model, workspace, options.maxSteps, permissionMode,
                contextBudget.requestTokenBudget(), contextBudget.triggerPercent(), contextBudget.imageTokenReserve(),
                reasoningEffort);
        if (options.yoloWarning) error.println("YOLO enabled: permissions disabled");
        ObjectMapper json = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(standardInput));
        ApprovalRouter approval = approvalOverride == null
                ? approvalRouter(config, input, error, console) : approvalOverride;
        SessionStore store = options.noSave ? null : new SessionStore(json, sessionRoot);
        ToolResultStore resultStore = new ToolResultStore(sessionRoot);
        Path mcpConfig = options.mcpConfig == null ? sessionRoot.resolve("mcp.json") : Path.of(options.mcpConfig);
        try (McpRuntime mcp = McpRuntime.load(json, mcpConfig)) {
        String systemPrompt = Agent.defaultSystemPrompt(config) + SkillTool.catalog(config.workspace(), sessionRoot);
        List<Tool> agentTools = new ArrayList<>(WorkspaceTools.create(config.workspace(), sessionRoot));
        agentTools.add(new InstallSkillTool(config.workspace(), sessionRoot));
        agentTools.add(new WebFetchTool());
        if (options.webSearch || Boolean.parseBoolean(environment.getOrDefault("JAVA_AGENT_WEB_SEARCH", "false"))) {
            agentTools.add(new HostedWebSearchTool());
        }
        agentTools.add(new AskUserTool(input, error, console != null, approval.questions()));
        agentTools.add(new ReadToolResultTool(resultStore));
        agentTools.addAll(mcp.tools());
        AtomicReference<List<Tool>> childTools = new AtomicReference<>();
        AtomicReference<SubagentManager> subagentRuntime = new AtomicReference<>();
        AtomicReference<SessionRuntime> activeSession = new AtomicReference<>();
        try (SubagentManager subagents = new SubagentManager(json, child ->
                new SubagentAgentRunner(json, config.apiKey(), config.baseUrl(), config.model(),
                        config.workspace(), config.maxSteps(), sessionRoot, childTools,
                        approval.childAuthority(() -> {
                            try {
                                return authorityRules(child, activeSession.get(), store);
                            } catch (IOException unavailable) {
                                throw new java.io.UncheckedIOException(unavailable);
                            }
                        }),
                        error, child,
                        subagentRuntime.get().parentContext(child.id()), config.contextBudget(),
                        () -> {
                            SessionRuntime active = activeSession.get();
                            return active == null ? new ModelSelection(config.model(), config.reasoningEffort())
                                    : active.modelSelection();
                        }, approval::permissionMode),
                approval::permissionMode, options.noSave ? null : sessionRoot,
                () -> activeSession.get() == null ? null : activeSession.get().id())) {
        subagentRuntime.set(subagents);
        agentTools.add(new SubagentTool(subagents));
        childTools.set(List.copyOf(agentTools));
        Agent agent = new Agent(json, new OpenAiResponsesClient(json, config),
                agentTools, approval, error, config.maxSteps(), systemPrompt, resultStore,
                subagents.parentContext("root"), config.contextBudget());
        SessionRuntime session = SessionRuntime.start(agent, store, config.workspace(), config.model(),
                systemPrompt, options.resume);
        if (options.model != null || notBlank(environment.get("OPENAI_MODEL"))
                || notBlank(environment.get("JAVA_AGENT_MODEL")) || preferences.model() != null) {
            session.setModel(model);
        }
        if (reasoningEffort != null) session.setReasoningEffort(reasoningEffort);
        approval.bindRules(session::rules, config.approveAll());
        approval.setPermissionMode(config.permissionMode());
        activeSession.set(session);
        subagents.restore();

        if (!options.prompt.isBlank()) {
            writeAnswer(session, options.prompt, out, options.json, json);
            return 0;
        }

        List<String> declined = new ArrayList<>();
        RawTerminal terminal = "dumb".equals(environment.get("TERM")) ? null
                : terminalOverride == null ? RawTerminal.open(declined::add) : terminalOverride;
        if (terminal == null) {
            String reason = "dumb".equals(environment.get("TERM")) ? "TERM=dumb"
                    : declined.isEmpty() ? "raw mode unavailable" : declined.get(0);
            error.println("java-agent: a raw terminal is required for the interactive shell: " + reason
                    + ". Run `java-agent doctor` for details, or use `java-agent ask <prompt>`.");
            return 2;
        }
        try (RawTerminal owned = terminal) {
            Ansi ansi = Ansi.fromEnvironment(environment, true);
            InteractiveShell shell = new InteractiveShell(session, config, systemPrompt, mcp,
                    sessionRoot, owned.input(standardInput), out, error, ansi, approval,
                    modelSource(options, environment, preferences, options.resume),
                    effortSource(options, environment, preferences), System::nanoTime);
            return shell.run(owned, TerminalCapabilities.detect(environment, true, owned::size));
        }
        }
        }
    }

    private static int runAcp(Options options, Map<String, String> environment, InputStream input,
                              PrintStream out, PrintStream error) throws Exception {
        if (options.noSave) {
            error.println("java-agent: ACP requires durable sessions; remove --no-save");
            return 2;
        }
        Path sessionRoot = sessionRoot(options, environment);
        UserPreferences preferences = loadPreferences(sessionRoot, error);
        String apiKey = firstNonBlank(environment.get("OPENAI_API_KEY"), environment.get("JAVA_AGENT_API_KEY"),
                preferences.apiKey());
        String baseUrl;
        try {
            baseUrl = BaseUrl.validate(firstNonBlank(configuredBaseUrl(options, environment, preferences),
                    BaseUrl.DEFAULT));
        } catch (IllegalArgumentException invalidBaseUrl) {
            error.println("java-agent: " + invalidBaseUrl.getMessage());
            return 2;
        }
        String model = firstNonBlank(options.model, environment.get("OPENAI_MODEL"),
                environment.get("JAVA_AGENT_MODEL"), preferences.model(), "gpt-5.6");
        String reasoningEffort;
        try {
            reasoningEffort = resolveEffort(options.effort, environment, preferences);
        } catch (IllegalArgumentException invalidEffort) {
            error.println("java-agent: " + invalidEffort.getMessage());
            return 2;
        }
        Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
        if (!Files.isDirectory(workspace)) {
            error.println("java-agent: workspace is not a directory: " + workspace);
            return 2;
        }
        PermissionMode ceiling = options.permissionMode != null ? options.permissionMode : PermissionMode.AUTO;
        Path mcpConfig = options.mcpConfig == null ? sessionRoot.resolve("mcp.json") : Path.of(options.mcpConfig);
        ObjectMapper json = new ObjectMapper();
        ContextBudget contextBudget = contextBudget(environment);
        AcpAgentBackend backend = new AcpAgentBackend(json, apiKey, baseUrl, model, workspace,
                options.maxSteps, ceiling, sessionRoot, mcpConfig,
                options.webSearch || Boolean.parseBoolean(environment.getOrDefault("JAVA_AGENT_WEB_SEARCH", "false")),
                contextBudget, reasoningEffort);
        new AcpServer(json, backend).serve(input, out);
        return 0;
    }

    private static int runInfoCommand(Options options, Map<String, String> environment,
                                      PrintStream out, PrintStream error)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        PermissionMode mode = options.permissionMode != null ? options.permissionMode
                : PermissionMode.parse(firstNonBlank(environment.get("JAVA_AGENT_PERMISSION_MODE"), "ask"));
        UserPreferences preferences = options.command.equals("doctor") || options.command.equals("status")
                ? loadPreferences(sessionRoot(options, environment), error)
                : UserPreferences.empty(sessionRoot(options, environment));
        ObjectNode result = json.createObjectNode().put("kind", options.command);
        switch (options.command) {
            case "status": {
                Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
                result.put("version", VERSION).put("workspace", workspace.toAbsolutePath().normalize().toString())
                        .put("model", firstNonBlank(options.model, environment.get("OPENAI_MODEL"),
                                environment.get("JAVA_AGENT_MODEL"), preferences.model(), "gpt-5.6"))
                        .put("base_url", firstNonBlank(configuredBaseUrl(options, environment, preferences),
                                BaseUrl.DEFAULT))
                        .put("base_url_source", baseUrlSource(options, environment, preferences))
                        .put("transport", "responses").put("gateway", false)
                        .put("permission_mode", mode.name().toLowerCase(java.util.Locale.ROOT))
                        .put("sandbox", "none")
                        .put("web_search", options.webSearch
                                || Boolean.parseBoolean(environment.getOrDefault("JAVA_AGENT_WEB_SEARCH", "false")));
                String effort = firstNonBlank(options.effort, environment.get("OPENAI_REASONING_EFFORT"),
                        environment.get("JAVA_AGENT_REASONING_EFFORT"), preferences.reasoningEffort());
                if (effort == null || effort.equalsIgnoreCase("default")) result.putNull("reasoning_effort");
                else result.put("reasoning_effort", effort.strip().toLowerCase(Locale.ROOT));
                break;
            }
            case "permissions": {
                result.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT))
                        .put("grant_count", 0).put("grant_scope", "session")
                        .put("runtime_grants_available", false).put("rules_scope", "none");
                result.putArray("rules");
                result.putArray("grants");
                break;
            }
            case "sessions": {
                String configured = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
                Path root = configured == null ? Path.of(System.getProperty("user.home"), ".java-agent")
                        : Path.of(configured);
                List<SessionStore.Snapshot> scanned = SessionStore.inspect(json, root)
                        .list(null, options.sessionCursor + options.sessionLimit + 1);
                int from = Math.min(options.sessionCursor, scanned.size());
                int to = Math.min(from + options.sessionLimit, scanned.size());
                List<SessionStore.Snapshot> snapshots = scanned.subList(from, to);
                boolean hasMore = to < scanned.size();
                result.put("count", snapshots.size()).put("has_more", hasMore);
                if (hasMore) result.put("next_cursor", Integer.toString(to + 1));
                ArrayNode listed = result.putArray("sessions");
                for (SessionStore.Snapshot snapshot : snapshots) {
                    listed.addObject().put("id", snapshot.id()).put("title", snapshot.title())
                            .put("workspace_root", snapshot.workspace()).put("model", snapshot.model())
                            .put("created_at_ms", snapshot.createdAt()).put("updated_at_ms", snapshot.updatedAt())
                            .put("history_len", snapshot.input().size());
                }
                break;
            }
            case "doctor": {
                ArrayNode checks = result.putArray("checks");
                checks.addObject().put("name", "java").put("status", "ok")
                        .put("detail", System.getProperty("java.version"));
                Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
                boolean workspaceOk = Files.isDirectory(workspace);
                checks.addObject().put("name", "workspace").put("status", workspaceOk ? "ok" : "fail")
                        .put("detail", workspace.toAbsolutePath().normalize().toString());
                boolean authenticated = firstNonBlank(environment.get("OPENAI_API_KEY"),
                        environment.get("JAVA_AGENT_API_KEY"), preferences.apiKey()) != null;
                checks.addObject().put("name", "auth").put("status", authenticated ? "ok" : "fail")
                        .put("detail", authenticated ? "OpenAI API key available" : "OpenAI API key is not configured");
                String terminal = terminalCheck(environment);
                boolean terminalOk = terminal.startsWith("raw mode available");
                checks.addObject().put("name", "terminal").put("status", terminalOk ? "ok" : "warn")
                        .put("detail", terminal);
                int failures = (workspaceOk ? 0 : 1) + (authenticated ? 0 : 1);
                int warnings = terminalOk ? 0 : 1;
                result.put("ok_count", 4 - failures - warnings).put("warn_count", warnings)
                        .put("fail_count", failures);
                break;
            }
            case "skills": {
                String configured = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
                Path root = configured == null ? Path.of(System.getProperty("user.home"), ".java-agent")
                        : Path.of(configured);
                Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
                SkillsCommand.populate(result, options.prompt, workspace, root, json);
                break;
            }
            case "config": {
                Path root = sessionRoot(options, environment);
                String[] words = options.prompt.trim().isEmpty() ? new String[0] : options.prompt.trim().split("\\s+");
                String usage = "Usage: config [show] | config set base-url <url> | config unset base-url";
                if (words.length == 0 || (words.length == 1 && words[0].equals("show"))) {
                    UserPreferences saved = loadPreferences(root, error);
                    result.put("file", saved.file().toString())
                            .put("api_key", saved.apiKey() == null ? "not saved" : "saved")
                            .put("base_url", firstNonBlank(configuredBaseUrl(options, environment, saved), BaseUrl.DEFAULT))
                            .put("base_url_source", baseUrlSource(options, environment, saved));
                    if (saved.baseUrl() != null) result.put("saved_base_url", saved.baseUrl());
                    if (saved.model() != null) result.put("saved_model", saved.model());
                    if (saved.reasoningEffort() != null) result.put("saved_reasoning_effort", saved.reasoningEffort());
                    break;
                }
                boolean set = words.length == 3 && words[0].equals("set") && words[1].equals("base-url");
                boolean unset = words.length == 2 && words[0].equals("unset") && words[1].equals("base-url");
                if (!set && !unset) throw new IllegalArgumentException(usage);
                // Load strictly: never rewrite a settings file (and its saved key) that could not be read.
                UserPreferences saved;
                try {
                    saved = UserPreferences.load(root);
                } catch (IOException unreadable) {
                    throw new IOException("saved settings could not be read securely; not changing them");
                }
                String value = set ? BaseUrl.validate(words[2]) : null;
                saved.withBaseUrl(value).save();
                result.put("file", saved.file().toString()).put("action", set ? "set" : "unset");
                if (set) result.put("base_url", value);
                String override = firstNonBlank(options.baseUrl, environment.get("OPENAI_BASE_URL"),
                        environment.get("JAVA_AGENT_BASE_URL"));
                if (override != null) {
                    result.put("note", "OPENAI_BASE_URL, JAVA_AGENT_BASE_URL, or --base-url still overrides the saved value");
                }
                break;
            }
            case "mcp": {
                String action = options.prompt.trim();
                if (!action.isEmpty() && !action.equals("list") && !action.equals("status")) {
                    throw new IllegalArgumentException("Usage: mcp [list|status]");
                }
                String configured = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
                Path root = configured == null ? Path.of(System.getProperty("user.home"), ".java-agent")
                        : Path.of(configured);
                Path config = options.mcpConfig == null ? root.resolve("mcp.json") : Path.of(options.mcpConfig);
                try (McpRuntime runtime = McpRuntime.inspect(json, config)) {
                    if (!options.json) {
                        out.print(runtime.healthText());
                        return 0;
                    }
                    result.setAll(runtime.healthReport());
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown command: " + options.command);
        }
        if (options.json) out.println(json.writeValueAsString(result));
        else result.properties().forEach(entry -> out.println(entry.getKey() + "="
                + (entry.getValue().isContainerNode() ? entry.getValue().toString() : entry.getValue().asText())));
        return 0;
    }

    private static void writeAnswer(SessionRuntime session, String prompt, PrintStream out,
                                    boolean structured, ObjectMapper json)
            throws IOException, InterruptedException {
        boolean[] streamed = { false };
        long[] turnUsage = new long[2];
        List<ImageAttachment> attached = session.pendingImages();
        if (!structured && !attached.isEmpty()) {
            out.println(ImageCommands.attachmentSummary(attached));
            out.flush();
        }
        String answer = session.prompt(prompt, delta -> {
            if (!structured) {
                streamed[0] = true;
                out.print(delta);
                out.flush();
            }
        }, new Agent.TurnListener() {
            @Override public void onToolStart(String name, String preview) { }

            @Override public void onToolEnd(String name, boolean error) { }

            @Override public void onUsage(long inputTokens, long outputTokens) {
                turnUsage[0] += inputTokens;
                turnUsage[1] += outputTokens;
            }
        });
        if (structured) {
            var result = json.createObjectNode().put("output", answer).put("exit_code", 0);
            if (session.id() != null) result.put("session_id", session.id());
            var calls = result.putArray("tool_calls");
            for (Agent.ToolCallRecord call : session.lastToolCalls()) {
                calls.addObject().put("name", call.name()).put("status", call.status());
            }
            out.println(json.writeValueAsString(result));
        } else {
            if (streamed[0]) out.println();
            else if (!answer.isBlank()) out.println(answer);
            out.println("tokens: " + StatsCommands.format(turnUsage[0], turnUsage[1]));
        }
    }

    private static ApprovalRouter approvalRouter(AgentConfig config, BufferedReader input,
                                                 PrintStream error, Console console) {
        ApprovalPolicy ask = approvalPolicy(config, input, error, console, PermissionMode.ASK);
        ApprovalRouter router = new ApprovalRouter(ask);
        router.setModeFallback(PermissionMode.ASK, ask);
        router.setModeFallback(PermissionMode.AUTO,
                approvalPolicy(config, input, error, console, PermissionMode.AUTO));
        router.setModeFallback(PermissionMode.YOLO,
                approvalPolicy(config, input, error, console, PermissionMode.YOLO));
        router.setPermissionMode(config.permissionMode());
        return router;
    }

    private static ApprovalPolicy approvalPolicy(AgentConfig config, BufferedReader input,
                                                  PrintStream error, Console console, PermissionMode mode) {
        if (mode == PermissionMode.YOLO) return (tool, arguments) -> true;
        if (mode == PermissionMode.AUTO) {
            return (tool, arguments) -> {
                boolean allowed;
                try {
                    allowed = tool.autoApprove(arguments);
                } catch (Exception invalid) {
                    allowed = false;
                }
                error.println("[auto-" + (allowed ? "approved] " : "denied] ") + tool.preview(arguments));
                return allowed;
            };
        }
        if (console == null) {
            return (tool, arguments) -> {
                error.println("[denied] " + tool.preview(arguments)
                        + " (non-interactive input; rerun with --yolo to allow unrestricted actions)");
                return false;
            };
        }
        return (tool, arguments) -> {
            error.print("Allow " + tool.preview(arguments) + "? [y/N] ");
            error.flush();
            try {
                String answer = input.readLine();
                return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
            } catch (IOException readError) {
                error.println("Could not read approval: " + safeMessage(readError));
                return false;
            }
        };
    }

    private static String modelSource(Options options, Map<String, String> environment,
                                     UserPreferences preferences, String resume) {
        if (options.model != null) return "--model flag";
        if (notBlank(environment.get("OPENAI_MODEL"))) return "env OPENAI_MODEL";
        if (notBlank(environment.get("JAVA_AGENT_MODEL"))) return "env JAVA_AGENT_MODEL";
        if (preferences.model() != null) return "saved preference";
        if (resume != null) return "saved session";
        return "default";
    }

    private static String effortSource(Options options, Map<String, String> environment,
                                       UserPreferences preferences) {
        if (options.effort != null) return "--effort flag";
        if (notBlank(environment.get("OPENAI_REASONING_EFFORT"))) return "env OPENAI_REASONING_EFFORT";
        if (notBlank(environment.get("JAVA_AGENT_REASONING_EFFORT"))) return "env JAVA_AGENT_REASONING_EFFORT";
        if (preferences.reasoningEffort() != null) return "saved preference";
        return "provider default";
    }

    private static String resolveEffort(String option, Map<String, String> environment,
                                        UserPreferences preferences) {
        String selected = firstNonBlank(option, environment.get("OPENAI_REASONING_EFFORT"),
                environment.get("JAVA_AGENT_REASONING_EFFORT"), preferences.reasoningEffort());
        if (selected == null || selected.equalsIgnoreCase("default")) return null;
        selected = selected.strip().toLowerCase(Locale.ROOT);
        if (!AgentConfig.reasoningEffortValues().contains(selected)) {
            throw new IllegalArgumentException("--effort must be one of "
                    + String.join(", ", AgentConfig.reasoningEffortValues()) + " (or default)");
        }
        return selected;
    }

    private static Path sessionRoot(Options options, Map<String, String> environment) {
        String configured = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
        return configured == null ? Path.of(System.getProperty("user.home"), ".java-agent")
                : Path.of(configured);
    }

    /** CLI, then environment, then saved settings; null means the built-in default. */
    private static String configuredBaseUrl(Options options, Map<String, String> environment,
                                            UserPreferences preferences) {
        return firstNonBlank(options.baseUrl, environment.get("OPENAI_BASE_URL"),
                environment.get("JAVA_AGENT_BASE_URL"), preferences.baseUrl());
    }

    private static String baseUrlSource(Options options, Map<String, String> environment,
                                        UserPreferences preferences) {
        if (notBlank(options.baseUrl)) return "cli";
        if (notBlank(environment.get("OPENAI_BASE_URL"))) return "env:OPENAI_BASE_URL";
        if (notBlank(environment.get("JAVA_AGENT_BASE_URL"))) return "env:JAVA_AGENT_BASE_URL";
        if (notBlank(preferences.baseUrl())) return "saved";
        return "default";
    }

    static String endpointHost(String baseUrl) {
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            return host == null ? baseUrl : host;
        } catch (IllegalArgumentException invalid) {
            return baseUrl;
        }
    }

    private static UserPreferences loadPreferences(Path root, PrintStream error) {
        try {
            return UserPreferences.load(root);
        } catch (IOException invalid) {
            error.println("java-agent: saved settings could not be read securely; ignoring them.");
            return UserPreferences.empty(root);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static SessionRules authorityRules(SubagentManager.ChildConfiguration child,
                                               SessionRuntime active, SessionStore store)
            throws IOException {
        String ownerId = child.authoritySessionId();
        if (ownerId == null) {
            if (store != null) throw new IOException("Subagent permission authority has no owning session");
            return new SessionRules();
        }
        if (active != null && ownerId.equals(active.id())) return active.rules();
        return store == null ? new SessionRules() : store.loadRules(ownerId);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static ContextBudget contextBudget(Map<String, String> environment) {
        return new ContextBudget(
                integerSetting(environment, "JAVA_AGENT_CONTEXT_BUDGET_TOKENS",
                        ContextBudget.DEFAULT_REQUEST_TOKEN_BUDGET, 1, 10_000_000),
                integerSetting(environment, "JAVA_AGENT_CONTEXT_TRIGGER_PERCENT",
                        ContextBudget.DEFAULT_TRIGGER_PERCENT, 1, 100),
                integerSetting(environment, "JAVA_AGENT_IMAGE_TOKEN_RESERVE",
                        ContextBudget.DEFAULT_IMAGE_TOKEN_RESERVE, 0, 10_000_000));
    }

    private static int integerSetting(Map<String, String> environment, String name,
                                      int fallback, int minimum, int maximum) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer between " + minimum + " and " + maximum);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be an integer between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String usage() {
        return "Usage:\n"
                + "  java -jar target/java-agent.jar [options] [ask] [prompt]\n"
                + "  java -jar target/java-agent.jar [options] acp\n"
                + "  java -jar target/java-agent.jar [options] skills [list|show|create|remove|install|path] [value]\n"
                + "  java -jar target/java-agent.jar [options] mcp [list|status]\n"
                + "  java -jar target/java-agent.jar [options] config [show|set base-url <url>|unset base-url]\n"
                + "\n"
                + "Options:\n"
                + "  --model <id>          OpenAI model (env: OPENAI_MODEL; default: gpt-5.6)\n"
                + "  --effort <level>      none|minimal|low|medium|high|xhigh|max|default (env: OPENAI_REASONING_EFFORT)\n"
                + "  --base-url <url>      API base URL (env: OPENAI_BASE_URL; saved: config set base-url)\n"
                + "  --workspace <path>    Workspace root (default: current directory)\n"
                + "  --max-steps <count>   Maximum response/tool iterations per turn (default: 0, unlimited)\n"
                + "  --resume <id|last>     Resume a saved session for this workspace\n"
                + "  --session-root <path>  Session storage root (env: JAVA_AGENT_HOME)\n"
                + "  --mcp-config <path>    MCP JSON config (default: <session-root>/mcp.json)\n"
                + "  --no-save             Disable session persistence\n"
                + "  --json                Emit one structured JSON ask result\n"
                + "  --web-search          Enable OpenAI-hosted Responses web search\n"
                + "  --ask                Prompt before sensitive actions (default)\n"
                + "  --auto               Auto-allow external reads; deny writes/commands\n"
                + "  --yolo               Allow all actions and print a warning\n"
                + "  --yes                Alias for --yolo\n"
                + "  --help                Show help\n"
                + "  --version             Show version\n"
                + "\n"
                + "Authentication:\n"
                + "  OPENAI_API_KEY (JAVA_AGENT_API_KEY is also accepted); interactive first run prompts securely\n"
                + "  OPENAI_MODEL (JAVA_AGENT_MODEL fallback); OPENAI_REASONING_EFFORT (JAVA_AGENT_REASONING_EFFORT fallback)\n"
                + "  Interactive selectors: /model [<id> [--save]], /effort [<level|default> [--save]]\n"
                + "\n"
                + "The harness uses POST /v1/responses with store=false.\n"
                + "Interactive keys: Tab completes /commands, Ctrl+C cancels (twice exits), /help lists more.\n"
                + "\n"
                + "Examples:\n"
                + "  java -jar target/java-agent.jar\n"
                + "  java -jar target/java-agent.jar ask \"Explain this repository\"\n"
                + "  java -jar target/java-agent.jar skills list\n"
                + "  java -jar target/java-agent.jar mcp list\n"
                + "  java -jar target/java-agent.jar --auto \"Explain an external file\"\n"
                + "  java -jar target/java-agent.jar --yolo \"Fix the failing tests\"\n";
    }

    private static final class Options {
        String baseUrl;
        String model;
        String effort;
        String workspace;
        String resume;
        String sessionRoot;
        String mcpConfig;
        int maxSteps = 0;
        int sessionLimit = 100;
        int sessionCursor;
        PermissionMode permissionMode;
        boolean yoloWarning;
        boolean noSave;
        boolean json;
        boolean webSearch;
        boolean help;
        boolean version;
        String prompt = "";
        String command;
        boolean explicitAsk;

        static Options parse(String[] args) {
            Options result = new Options();
            List<String> prompt = new ArrayList<>();
            boolean literal = false;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (literal) {
                    prompt.add(argument);
                    continue;
                }
                switch (argument) {
                    case "--":
                        literal = true;
                        break;
                    case "ask": {
                        if (prompt.isEmpty() && result.command == null) result.explicitAsk = true;
                        else prompt.add(argument);
                        break;
                    }
                    case "status":
                    case "permissions":
                    case "doctor":
                    case "sessions":
                    case "skills":
                    case "mcp":
                    case "config":
                    case "acp": {
                        if (!result.explicitAsk && prompt.isEmpty() && result.command == null) result.command = argument;
                        else prompt.add(argument);
                        break;
                    }
                    case "--model": result.model = requireValue(args, ++index, argument); break;
                    case "--effort": result.effort = requireValue(args, ++index, argument); break;
                    case "--base-url": result.baseUrl = requireValue(args, ++index, argument); break;
                    case "--workspace": result.workspace = requireValue(args, ++index, argument); break;
                    case "--resume": result.resume = requireValue(args, ++index, argument); break;
                    case "--session-root": result.sessionRoot = requireValue(args, ++index, argument); break;
                    case "--mcp-config": result.mcpConfig = requireValue(args, ++index, argument); break;
                    case "--limit":
                        result.sessionLimit = positiveInt(requireValue(args, ++index, argument), argument, 100);
                        break;
                    case "--cursor":
                        result.sessionCursor = positiveInt(
                                requireValue(args, ++index, argument), argument, 1_000_000) - 1;
                        break;
                    case "--max-steps": {
                        String value = requireValue(args, ++index, argument);
                        try {
                            result.maxSteps = Integer.parseInt(value);
                        } catch (NumberFormatException error) {
                            throw new IllegalArgumentException("--max-steps must be an integer");
                        }
                        break;
                    }
                    case "--ask": result.permissionMode = PermissionMode.ASK; break;
                    case "--auto": result.permissionMode = PermissionMode.AUTO; break;
                    case "--yes":
                    case "-y": result.permissionMode = PermissionMode.YOLO; break;
                    case "--yolo": {
                        result.permissionMode = PermissionMode.YOLO;
                        result.yoloWarning = true;
                        break;
                    }
                    case "--no-save": result.noSave = true; break;
                    case "--json": result.json = true; break;
                    case "--web-search": result.webSearch = true; break;
                    case "--help":
                    case "-h": result.help = true; break;
                    case "--version": result.version = true; break;
                    default: {
                        if (argument.startsWith("-") && !"skills".equals(result.command)) {
                            throw new IllegalArgumentException("Unknown option: " + argument);
                        }
                        prompt.add(argument);
                        break;
                    }
                }
            }
            result.prompt = String.join(" ", prompt);
            return result;
        }

        private static int positiveInt(String value, String option, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1 || parsed > maximum) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(option + " must be between 1 and " + maximum);
            }
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
            return args[index];
        }
    }
}
