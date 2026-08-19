package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        Options options = Options.parse(args);
        if (options.help) {
            out.print(usage());
            return 0;
        }
        if (options.version) {
            out.println("java-agent " + VERSION);
            return 0;
        }

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

        AgentConfig config = new AgentConfig(apiKey, baseUrl, model, workspace, options.maxSteps, options.yes);
        ObjectMapper json = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        ApprovalPolicy approval = approvalPolicy(config, input, error, System.console());
        Agent agent = new Agent(json, new OpenAiResponsesClient(json, config),
                WorkspaceTools.create(config.workspace()), approval, error, config.maxSteps(),
                Agent.defaultSystemPrompt(config));

        if (!options.prompt.isBlank()) {
            String answer = agent.prompt(options.prompt);
            if (!answer.isBlank()) out.println(answer);
            return 0;
        }

        out.println("java-agent " + VERSION + " | Responses API | " + config.model()
                + " | " + config.workspace());
        out.println("Enter a request. Commands: /clear, /exit");
        while (true) {
            out.print("> ");
            out.flush();
            String line = input.readLine();
            if (line == null || line.equals("/exit") || line.equals("/quit")) break;
            if (line.equals("/clear")) {
                agent.clearConversation(Agent.defaultSystemPrompt(config));
                out.println("Conversation cleared.");
            } else if (!line.isBlank()) {
                try {
                    String answer = agent.prompt(line);
                    if (!answer.isBlank()) out.println(answer);
                } catch (IOException errorResponse) {
                    error.println("java-agent: " + safeMessage(errorResponse));
                }
            }
        }
        return 0;
    }

    private static ApprovalPolicy approvalPolicy(AgentConfig config, BufferedReader input,
                                                   PrintStream error, Console console) {
        if (config.approveAll()) return (tool, arguments) -> true;
        if (console == null) {
            return (tool, arguments) -> {
                error.println("[denied] " + tool.preview(arguments)
                        + " (non-interactive input; rerun with --yes to allow mutations)");
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

                Options:
                  --model <id>          OpenAI model (env: OPENAI_MODEL; default: gpt-5.6)
                  --base-url <url>      OpenAI API base URL (env: OPENAI_BASE_URL)
                  --workspace <path>    Workspace root (default: current directory)
                  --max-steps <count>   Maximum response/tool iterations, 1-100 (default: 20)
                  --yes                 Approve workspace mutations and shell commands
                  --help                Show help
                  --version             Show version

                Authentication:
                  OPENAI_API_KEY (JAVA_AGENT_API_KEY is also accepted)

                The harness uses POST /v1/responses with store=false.

                Examples:
                  java -jar target/java-agent.jar
                  java -jar target/java-agent.jar ask "Explain this repository"
                  java -jar target/java-agent.jar --yes "Fix the failing tests"
                """;
    }

    private static final class Options {
        String baseUrl;
        String model;
        String workspace;
        int maxSteps = 20;
        boolean yes;
        boolean help;
        boolean version;
        String prompt = "";

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
                        if (!prompt.isEmpty()) prompt.add(argument);
                    }
                    case "--model" -> result.model = requireValue(args, ++index, argument);
                    case "--base-url" -> result.baseUrl = requireValue(args, ++index, argument);
                    case "--workspace" -> result.workspace = requireValue(args, ++index, argument);
                    case "--max-steps" -> {
                        String value = requireValue(args, ++index, argument);
                        try {
                            result.maxSteps = Integer.parseInt(value);
                        } catch (NumberFormatException error) {
                            throw new IllegalArgumentException("--max-steps must be an integer");
                        }
                    }
                    case "--yes", "-y" -> result.yes = true;
                    case "--help", "-h" -> result.help = true;
                    case "--version" -> result.version = true;
                    default -> {
                        if (argument.startsWith("-")) throw new IllegalArgumentException("Unknown option: " + argument);
                        prompt.add(argument);
                    }
                }
            }
            result.prompt = String.join(" ", prompt);
            return result;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
            return args[index];
        }
    }
}
