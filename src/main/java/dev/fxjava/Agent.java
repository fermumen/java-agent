package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stateful local agent loop implemented over stateless OpenAI Responses calls. */
public final class Agent {
    private final ObjectMapper json;
    private final ResponsesClient client;
    private final Map<String, Tool> tools;
    private final ArrayNode toolDefinitions;
    private final ArrayNode inputHistory;
    private final ApprovalPolicy approvalPolicy;
    private final PrintStream progress;
    private final int maxSteps;
    private String instructions;

    public Agent(ObjectMapper json, ResponsesClient client, List<Tool> tools,
                 ApprovalPolicy approvalPolicy, PrintStream progress, int maxSteps,
                 String systemPrompt) {
        this.json = json;
        this.client = client;
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));
        this.toolDefinitions = buildToolDefinitions(tools);
        this.inputHistory = json.createArrayNode();
        this.approvalPolicy = approvalPolicy;
        this.progress = progress;
        this.maxSteps = maxSteps;
        this.instructions = systemPrompt;
    }

    public String prompt(String input) throws IOException, InterruptedException {
        addUserMessage(input);
        for (int step = 0; step < maxSteps; step++) {
            ObjectNode response = client.complete(inputHistory.deepCopy(), toolDefinitions.deepCopy(), instructions);
            ArrayNode output = (ArrayNode) response.path("output");
            List<JsonNode> functionCalls = new ArrayList<>();

            for (JsonNode item : output) {
                inputHistory.add(item.deepCopy());
                if (item.path("type").asText().equals("function_call")) functionCalls.add(item);
            }

            if (functionCalls.isEmpty()) return extractOutputText(output);
            for (JsonNode call : functionCalls) executeToolCall(call);
        }
        throw new IOException("Agent stopped after reaching the " + maxSteps + " step limit");
    }

    public void clearConversation(String systemPrompt) {
        inputHistory.removeAll();
        instructions = systemPrompt;
    }

    private void executeToolCall(JsonNode call) throws IOException, InterruptedException {
        String callId = call.path("call_id").asText();
        String name = call.path("name").asText();
        String rawArguments = call.path("arguments").asText("{}");
        if (callId.isBlank()) throw new IOException("OpenAI returned a function call without call_id");

        String result;
        Tool tool = tools.get(name);
        if (tool == null) {
            result = "Error: unknown tool '" + name + "'";
        } else {
            try {
                JsonNode arguments = json.readTree(rawArguments);
                if (arguments == null || !arguments.isObject()) {
                    throw new IllegalArgumentException("tool arguments must be a JSON object");
                }
                progress.println("[tool] " + tool.preview(arguments));
                if (tool.requiresApproval() && !approvalPolicy.approve(tool, arguments)) {
                    result = "Error: user denied this tool call";
                } else {
                    result = tool.execute(arguments);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception error) {
                result = "Error: " + safeMessage(error);
            }
        }

        ObjectNode toolOutput = inputHistory.addObject();
        toolOutput.put("type", "function_call_output");
        toolOutput.put("call_id", callId);
        toolOutput.put("output", result);
    }

    private ArrayNode buildToolDefinitions(List<Tool> availableTools) {
        ArrayNode definitions = json.createArrayNode();
        for (Tool tool : availableTools) {
            ObjectNode definition = definitions.addObject();
            definition.put("type", "function");
            definition.put("name", tool.name());
            definition.put("description", tool.description());
            definition.set("parameters", tool.parameters().deepCopy());
        }
        return definitions;
    }

    private void addUserMessage(String content) {
        ObjectNode message = inputHistory.addObject();
        message.put("role", "user");
        message.put("content", content);
    }

    private static String extractOutputText(ArrayNode output) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if (!item.path("type").asText().equals("message")) continue;
            for (JsonNode content : item.path("content")) {
                String type = content.path("type").asText();
                String value = type.equals("refusal")
                        ? content.path("refusal").asText()
                        : content.path("text").asText();
                if (!value.isBlank()) text.append(value);
            }
        }
        return text.toString();
    }

    public static String defaultSystemPrompt(AgentConfig config) {
        return """
                You are a coding agent working in a local repository. Work autonomously toward the user's request.
                Inspect relevant files before changing them. Keep edits focused, preserve existing work, and verify changes.
                Use the provided tools instead of inventing file contents or command results. Never claim to have run a
                command you did not run. File tools are constrained to the workspace. Destructive or command actions may
                require approval. If a tool fails, reason from the error and choose a safe alternative.

                Workspace: %s
                Current date: %s
                """.formatted(config.workspace(), LocalDate.now());
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
