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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    private static final String VERSION = "0.2.0";

    private Main() {
    }

    public static void main(String[] args) {
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
        if (options.command != null) return runInfoCommand(options, environment, out);

        String apiKey = firstNonBlank(environment.get("OPENAI_API_KEY"),
                environment.get("JAVA_AGENT_API_KEY"));
        if (apiKey == null) {
            error.println("java-agent: set OPENAI_API_KEY (or JAVA_AGENT_API_KEY)");
            return 2;
        }
        String baseUrl = firstNonBlank(options.baseUrl, environment.get("OPENAI_BASE_URL"),
                environment.get("JAVA_AGENT_BASE_URL"), "https://api.openai.com/v1");
        String model = firstNonBlank(options.model, environment.get("OPENAI_MODEL"),
                environment.get("JAVA_AGENT_MODEL"), "gpt-5.6");
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
        AgentConfig config = new AgentConfig(apiKey, baseUrl, model, workspace, options.maxSteps, permissionMode);
        if (options.yoloWarning) error.println("YOLO enabled: permissions disabled");
        ObjectMapper json = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(standardInput));
        Console console = System.console();
        ApprovalPolicy approval = approvalPolicy(config, input, error, console);
        String configuredRoot = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
        Path sessionRoot = configuredRoot == null
                ? Path.of(System.getProperty("user.home"), ".java-agent") : Path.of(configuredRoot);
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
        agentTools.add(new AskUserTool(input, error, console != null));
        agentTools.add(new ReadToolResultTool(resultStore));
        agentTools.addAll(mcp.tools());
        AtomicReference<List<Tool>> childTools = new AtomicReference<>();
        AtomicReference<SubagentManager> subagentRuntime = new AtomicReference<>();
        try (SubagentManager subagents = new SubagentManager(json, child ->
                new SubagentAgentRunner(json, config.apiKey(), config.baseUrl(), config.model(),
                        config.workspace(), config.maxSteps(), sessionRoot, childTools, approval, error, child,
                        subagentRuntime.get().parentContext(child.id())),
                permissionMode, options.noSave ? null : sessionRoot)) {
        subagentRuntime.set(subagents);
        agentTools.add(new SubagentTool(subagents));
        childTools.set(List.copyOf(agentTools));
        subagents.restore();
        Agent agent = new Agent(json, new OpenAiResponsesClient(json, config),
                agentTools, approval, error, config.maxSteps(), systemPrompt, resultStore,
                subagents.parentContext("root"));
        SessionRuntime session = SessionRuntime.start(agent, store, config.workspace(), config.model(),
                systemPrompt, options.resume);

        if (!options.prompt.isBlank()) {
            writeAnswer(session, options.prompt, out, options.json, json);
            return 0;
        }

        out.println("java-agent " + VERSION + " | Responses API | " + config.model()
                + " | " + config.workspace());
        if (session.id() != null) out.println("Session: " + session.id());
        out.println("Enter a request. Commands: /new, /clear, /sessions, /resume <id|last>, /recover <id>, /rename <title>, /mcp list, /exit");
        while (true) {
            out.print("> ");
            out.flush();
            String line = input.readLine();
            if (line == null || line.equals("/exit") || line.equals("/quit")) break;
            boolean persistedCommand = line.equals("/new") || line.equals("/sessions")
                    || line.equals("/resume") || line.startsWith("/resume ")
                    || line.startsWith("/recover ") || line.startsWith("/rename ");
            if (persistedCommand && !session.persistent()) {
                out.println("Session persistence is disabled by --no-save.");
                continue;
            }
            if (line.equals("/clear")) {
                session.clear(systemPrompt);
                out.println("Conversation cleared.");
            } else if (line.equals("/new")) {
                session.newSession(config.workspace(), config.model(), systemPrompt);
                out.println("New session: " + session.id());
            } else if (line.equals("/resume") || line.startsWith("/resume ")) {
                String id = line.equals("/resume") ? "last" : line.substring("/resume ".length()).trim();
                session.resume(id, config.workspace());
                out.println("Resumed session: " + session.id());
            } else if (line.startsWith("/recover ")) {
                session.recover(line.substring("/recover ".length()).trim(), config.workspace());
                out.println("Recovered as: " + session.id());
            } else if (line.equals("/sessions")) {
                List<SessionStore.Snapshot> snapshots = session.sessions(config.workspace(), 20);
                if (snapshots.isEmpty()) out.println("No saved sessions.");
                for (SessionStore.Snapshot saved : snapshots) {
                    String current = saved.id().equals(session.id()) ? " *" : "";
                    String title = saved.title().isBlank() ? "" : "  " + saved.title();
                    out.println(saved.id() + current + title);
                }
            } else if (line.startsWith("/rename ")) {
                session.rename(line.substring("/rename ".length()));
                out.println("Session renamed.");
            } else if (line.equals("/mcp") || line.equals("/mcp list")) {
                out.print(mcp.healthText());
            } else if (!line.isBlank()) {
                try {
                    writeAnswer(session, line, out, false, json);
                } catch (IOException errorResponse) {
                    error.println("java-agent: " + safeMessage(errorResponse));
                }
            }
        }
        return 0;
        }
        }
    }

    private static int runAcp(Options options, Map<String, String> environment, InputStream input,
                              PrintStream out, PrintStream error) throws Exception {
        if (options.noSave) {
            error.println("java-agent: ACP requires durable sessions; remove --no-save");
            return 2;
        }
        String apiKey = firstNonBlank(environment.get("OPENAI_API_KEY"), environment.get("JAVA_AGENT_API_KEY"));
        String baseUrl = firstNonBlank(options.baseUrl, environment.get("OPENAI_BASE_URL"),
                environment.get("JAVA_AGENT_BASE_URL"), "https://api.openai.com/v1");
        String model = firstNonBlank(options.model, environment.get("OPENAI_MODEL"),
                environment.get("JAVA_AGENT_MODEL"), "gpt-5.6");
        Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
        if (!Files.isDirectory(workspace)) {
            error.println("java-agent: workspace is not a directory: " + workspace);
            return 2;
        }
        PermissionMode ceiling = options.permissionMode != null ? options.permissionMode : PermissionMode.AUTO;
        String configuredRoot = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
        Path sessionRoot = configuredRoot == null
                ? Path.of(System.getProperty("user.home"), ".java-agent") : Path.of(configuredRoot);
        Path mcpConfig = options.mcpConfig == null ? sessionRoot.resolve("mcp.json") : Path.of(options.mcpConfig);
        ObjectMapper json = new ObjectMapper();
        AcpAgentBackend backend = new AcpAgentBackend(json, apiKey, baseUrl, model, workspace,
                options.maxSteps, ceiling, sessionRoot, mcpConfig,
                options.webSearch || Boolean.parseBoolean(environment.getOrDefault("JAVA_AGENT_WEB_SEARCH", "false")));
        new AcpServer(json, backend).serve(input, out);
        return 0;
    }

    private static int runInfoCommand(Options options, Map<String, String> environment, PrintStream out)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        PermissionMode mode = options.permissionMode != null ? options.permissionMode
                : PermissionMode.parse(firstNonBlank(environment.get("JAVA_AGENT_PERMISSION_MODE"), "ask"));
        ObjectNode result = json.createObjectNode().put("kind", options.command);
        switch (options.command) {
            case "status" -> {
                Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
                result.put("version", VERSION).put("workspace", workspace.toAbsolutePath().normalize().toString())
                        .put("model", firstNonBlank(options.model, environment.get("OPENAI_MODEL"),
                                environment.get("JAVA_AGENT_MODEL"), "gpt-5.6"))
                        .put("transport", "responses").put("gateway", false)
                        .put("permission_mode", mode.name().toLowerCase(java.util.Locale.ROOT))
                        .put("sandbox", "none")
                        .put("web_search", options.webSearch
                                || Boolean.parseBoolean(environment.getOrDefault("JAVA_AGENT_WEB_SEARCH", "false")));
            }
            case "permissions" -> {
                result.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT))
                        .put("grant_count", 0).put("grant_scope", "session")
                        .put("runtime_grants_available", false).put("rules_scope", "persistent_config");
                result.putArray("rules");
                result.putArray("grants");
            }
            case "sessions" -> {
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
            }
            case "doctor" -> {
                ArrayNode checks = result.putArray("checks");
                checks.addObject().put("name", "java").put("status", "ok")
                        .put("detail", System.getProperty("java.version"));
                Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
                boolean workspaceOk = Files.isDirectory(workspace);
                checks.addObject().put("name", "workspace").put("status", workspaceOk ? "ok" : "fail")
                        .put("detail", workspace.toAbsolutePath().normalize().toString());
                boolean authenticated = firstNonBlank(environment.get("OPENAI_API_KEY"),
                        environment.get("JAVA_AGENT_API_KEY")) != null;
                checks.addObject().put("name", "auth").put("status", authenticated ? "ok" : "fail")
                        .put("detail", authenticated ? "OpenAI API key available" : "OpenAI API key is not configured");
                int failures = (workspaceOk ? 0 : 1) + (authenticated ? 0 : 1);
                result.put("ok_count", 3 - failures).put("warn_count", 0).put("fail_count", failures);
            }
            case "skills" -> {
                String configured = firstNonBlank(options.sessionRoot, environment.get("JAVA_AGENT_HOME"));
                Path root = configured == null ? Path.of(System.getProperty("user.home"), ".java-agent")
                        : Path.of(configured);
                Path workspace = options.workspace == null ? Path.of("") : Path.of(options.workspace);
                SkillsCommand.populate(result, options.prompt, workspace, root, json);
            }
            case "mcp" -> {
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
            }
            default -> throw new IllegalArgumentException("Unknown command: " + options.command);
        }
        if (options.json) out.println(json.writeValueAsString(result));
        else result.properties().forEach(entry -> out.println(entry.getKey() + "=" + entry.getValue().asText()));
        return 0;
    }

    private static void writeAnswer(SessionRuntime session, String prompt, PrintStream out,
                                    boolean structured, ObjectMapper json)
            throws IOException, InterruptedException {
        boolean[] streamed = { false };
        String answer = session.prompt(prompt, delta -> {
            if (!structured) {
                streamed[0] = true;
                out.print(delta);
                out.flush();
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
        } else if (streamed[0]) out.println();
        else if (!answer.isBlank()) out.println(answer);
    }

    private static ApprovalPolicy approvalPolicy(AgentConfig config, BufferedReader input,
                                                   PrintStream error, Console console) {
        if (config.approveAll()) return (tool, arguments) -> true;
        if (config.permissionMode() == PermissionMode.AUTO) {
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String usage() {
        return """
                Usage:
                  java -jar target/java-agent.jar [options] [ask] [prompt]
                  java -jar target/java-agent.jar [options] acp
                  java -jar target/java-agent.jar [options] skills [list|show|create|remove|install|path] [value]
                  java -jar target/java-agent.jar [options] mcp [list|status]

                Options:
                  --model <id>          OpenAI model (env: OPENAI_MODEL; default: gpt-5.6)
                  --base-url <url>      OpenAI API base URL (env: OPENAI_BASE_URL)
                  --workspace <path>    Workspace root (default: current directory)
                  --max-steps <count>   Maximum response/tool iterations, 1-100 (default: 20)
                  --resume <id|last>     Resume a saved session for this workspace
                  --session-root <path>  Session storage root (env: JAVA_AGENT_HOME)
                  --mcp-config <path>    MCP JSON config (default: <session-root>/mcp.json)
                  --no-save             Disable session persistence
                  --json                Emit one structured JSON ask result
                  --web-search          Enable OpenAI-hosted Responses web search
                  --ask                Prompt before sensitive actions (default)
                  --auto               Auto-allow external reads; deny writes/commands
                  --yolo               Allow all actions and print a warning
                  --yes                Alias for --yolo
                  --help                Show help
                  --version             Show version

                Authentication:
                  OPENAI_API_KEY (JAVA_AGENT_API_KEY is also accepted)

                The harness uses POST /v1/responses with store=false.

                Examples:
                  java -jar target/java-agent.jar
                  java -jar target/java-agent.jar ask "Explain this repository"
                  java -jar target/java-agent.jar skills list
                  java -jar target/java-agent.jar mcp list
                  java -jar target/java-agent.jar --auto "Explain an external file"
                  java -jar target/java-agent.jar --yolo "Fix the failing tests"
                """;
    }

    private static final class Options {
        String baseUrl;
        String model;
        String workspace;
        String resume;
        String sessionRoot;
        String mcpConfig;
        int maxSteps = 20;
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
                    case "--" -> literal = true;
                    case "ask" -> {
                        if (prompt.isEmpty() && result.command == null) result.explicitAsk = true;
                        else prompt.add(argument);
                    }
                    case "status", "permissions", "doctor", "sessions", "skills", "mcp", "acp" -> {
                        if (!result.explicitAsk && prompt.isEmpty() && result.command == null) result.command = argument;
                        else prompt.add(argument);
                    }
                    case "--model" -> result.model = requireValue(args, ++index, argument);
                    case "--base-url" -> result.baseUrl = requireValue(args, ++index, argument);
                    case "--workspace" -> result.workspace = requireValue(args, ++index, argument);
                    case "--resume" -> result.resume = requireValue(args, ++index, argument);
                    case "--session-root" -> result.sessionRoot = requireValue(args, ++index, argument);
                    case "--mcp-config" -> result.mcpConfig = requireValue(args, ++index, argument);
                    case "--limit" -> result.sessionLimit = positiveInt(requireValue(args, ++index, argument), argument, 100);
                    case "--cursor" -> result.sessionCursor = positiveInt(requireValue(args, ++index, argument), argument, 1_000_000) - 1;
                    case "--max-steps" -> {
                        String value = requireValue(args, ++index, argument);
                        try {
                            result.maxSteps = Integer.parseInt(value);
                        } catch (NumberFormatException error) {
                            throw new IllegalArgumentException("--max-steps must be an integer");
                        }
                    }
                    case "--ask" -> result.permissionMode = PermissionMode.ASK;
                    case "--auto" -> result.permissionMode = PermissionMode.AUTO;
                    case "--yes", "-y" -> result.permissionMode = PermissionMode.YOLO;
                    case "--yolo" -> {
                        result.permissionMode = PermissionMode.YOLO;
                        result.yoloWarning = true;
                    }
                    case "--no-save" -> result.noSave = true;
                    case "--json" -> result.json = true;
                    case "--web-search" -> result.webSearch = true;
                    case "--help", "-h" -> result.help = true;
                    case "--version" -> result.version = true;
                    default -> {
                        if (argument.startsWith("-") && !"skills".equals(result.command)) {
                            throw new IllegalArgumentException("Unknown option: " + argument);
                        }
                        prompt.add(argument);
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
