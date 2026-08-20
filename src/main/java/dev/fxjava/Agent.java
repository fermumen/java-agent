package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/** Stateful local agent loop implemented over stateless OpenAI Responses calls. */
public final class Agent {
    private final ObjectMapper json;
    private final ResponsesClient client;
    private final Map<String, Tool> tools;
    private final List<Tool> toolCatalog;
    private final List<ToolCallRecord> lastToolCalls = new ArrayList<>();
    private final ArrayNode inputHistory;
    private final ApprovalPolicy approvalPolicy;
    private final PrintStream progress;
    private final int maxSteps;
    private final ToolResultStore resultStore;
    private final ParentContext parentContext;
    private String instructions;

    public Agent(ObjectMapper json, ResponsesClient client, List<Tool> tools,
                 ApprovalPolicy approvalPolicy, PrintStream progress, int maxSteps,
                 String systemPrompt) {
        this(json, client, tools, approvalPolicy, progress, maxSteps, systemPrompt, null);
    }

    public Agent(ObjectMapper json, ResponsesClient client, List<Tool> tools,
                 ApprovalPolicy approvalPolicy, PrintStream progress, int maxSteps,
                 String systemPrompt, ToolResultStore resultStore) {
        this(json, client, tools, approvalPolicy, progress, maxSteps, systemPrompt, resultStore, null);
    }

    Agent(ObjectMapper json, ResponsesClient client, List<Tool> tools,
          ApprovalPolicy approvalPolicy, PrintStream progress, int maxSteps,
          String systemPrompt, ToolResultStore resultStore, ParentContext parentContext) {
        this.json = json;
        this.client = client;
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));
        this.toolCatalog = List.copyOf(tools);
        this.inputHistory = json.createArrayNode();
        this.approvalPolicy = approvalPolicy;
        this.progress = progress;
        this.maxSteps = maxSteps;
        this.instructions = systemPrompt;
        this.resultStore = resultStore;
        this.parentContext = parentContext;
    }

    public String prompt(String input) throws IOException, InterruptedException {
        return prompt(input, ignored -> { });
    }

    public String prompt(String input, Consumer<String> textDelta)
            throws IOException, InterruptedException {
        lastToolCalls.clear();
        addUserMessage(input);
        for (int step = 0; step < maxSteps; step++) {
            ArrayNode requestInput = inputHistory.deepCopy();
            PreparedParentContext prepared = parentContext == null ? null : parentContext.prepare();
            if (prepared != null && !prepared.content().isBlank()) {
                ObjectNode context = json.createObjectNode().put("role", "system")
                        .put("content", prepared.content());
                requestInput.insert(0, context);
            }
            ObjectNode response = client.complete(requestInput, buildToolDefinitions(toolCatalog), instructions, textDelta);
            if (prepared != null) parentContext.acknowledge(prepared);
            ArrayNode output = (ArrayNode) response.path("output");
            List<JsonNode> functionCalls = new ArrayList<>();

            for (JsonNode item : output) {
                inputHistory.add(persistable(item));
                if (item.path("type").asText().equals("function_call")) functionCalls.add(item);
            }

            if (functionCalls.isEmpty()) return extractOutputText(output);
            for (JsonNode call : functionCalls) executeToolCall(call);
        }
        throw new IOException("Agent stopped after reaching the " + maxSteps + " step limit");
    }

    public ArrayNode snapshotInput() {
        return inputHistory.deepCopy();
    }

    public List<ToolCallRecord> lastToolCalls() {
        return List.copyOf(lastToolCalls);
    }

    public String instructions() {
        return instructions;
    }

    public void restoreConversation(ArrayNode input, String systemPrompt) {
        if (input == null || systemPrompt == null) {
            throw new IllegalArgumentException("session input and instructions are required");
        }
        inputHistory.removeAll();
        for (JsonNode item : input) inputHistory.add(item.deepCopy());
        repairInterruptedToolCalls();
        instructions = systemPrompt;
    }

    public void clearConversation(String systemPrompt) {
        inputHistory.removeAll();
        instructions = systemPrompt;
    }

    private void repairInterruptedToolCalls() {
        Set<String> pending = new LinkedHashSet<>();
        for (JsonNode item : inputHistory) {
            String callId = item.path("call_id").asText();
            if (callId.isBlank()) continue;
            if (item.path("type").asText().equals("function_call")) pending.add(callId);
            if (item.path("type").asText().equals("function_call_output")) pending.remove(callId);
        }
        for (String callId : pending) {
            ObjectNode output = inputHistory.addObject();
            output.put("type", "function_call_output");
            output.put("call_id", callId);
            output.put("output", "Error: previous tool execution was interrupted before completion");
        }
    }

    private void executeToolCall(JsonNode call) throws IOException, InterruptedException {
        String callId = call.path("call_id").asText();
        String name = call.path("name").asText();
        String rawArguments = call.path("arguments").asText("{}");
        if (callId.isBlank()) throw new IOException("OpenAI returned a function call without call_id");

        String result;
        Tool tool = resolveTool(name);
        if (tool == null || !tool.advertised()) {
            result = "Error: unknown tool '" + name + "'";
        } else {
            try {
                JsonNode arguments = json.readTree(rawArguments);
                if (arguments == null || !arguments.isObject()) {
                    throw new IllegalArgumentException("tool arguments must be a JSON object");
                }
                progress.println("[tool] " + tool.preview(arguments));
                if (tool.requiresApproval(arguments) && !approvalPolicy.approve(tool, arguments)) {
                    result = "Error: user denied this tool call";
                } else {
                    result = tool.execute(arguments, callId);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception error) {
                result = "Error: " + safeMessage(error);
            }
        }

        boolean toolError = tool == null || tool.isErrorResult(result);
        if (resultStore != null && !name.equals("read_tool_result")) {
            result = resultStore.prepare(callId, name, result);
        }

        lastToolCalls.add(new ToolCallRecord(name, toolError ? "error" : "success"));
        ObjectNode toolOutput = inputHistory.addObject();
        toolOutput.put("type", "function_call_output");
        toolOutput.put("call_id", callId);
        toolOutput.put("output", result);
    }

    private Tool resolveTool(String name) throws IOException {
        Tool fixed = tools.get(name);
        if (fixed != null) return fixed;
        for (Tool candidate : toolCatalog) {
            if (candidate instanceof DynamicToolProvider) {
                DynamicToolProvider provider = (DynamicToolProvider) candidate;
                Tool dynamic = provider.resolveDynamicTool(name);
                if (dynamic != null) return dynamic;
            }
        }
        return null;
    }

    private ArrayNode buildToolDefinitions(List<Tool> availableTools) throws IOException {
        ArrayNode definitions = json.createArrayNode();
        Set<String> names = new LinkedHashSet<>();
        for (Tool tool : availableTools) addToolDefinition(definitions, names, tool);
        for (Tool tool : availableTools) {
            if (tool instanceof DynamicToolProvider) {
                DynamicToolProvider provider = (DynamicToolProvider) tool;
                for (Tool dynamic : provider.dynamicTools()) addToolDefinition(definitions, names, dynamic);
            }
        }
        return definitions;
    }

    private void addToolDefinition(ArrayNode definitions, Set<String> names, Tool tool) {
        if (tool.advertised() && names.add(tool.name())) definitions.add(tool.definition(json));
    }

    private void addUserMessage(String content) {
        ObjectNode message = inputHistory.addObject();
        message.put("role", "user");
        message.put("content", content);
    }

    private JsonNode persistable(JsonNode item) {
        JsonNode copy = item.deepCopy();
        if (copy instanceof ObjectNode
                && copy.path("type").asText().equals("function_call")
                && copy.path("arguments").isTextual()) {
            ObjectNode object = (ObjectNode) copy;
            object.put("arguments", SecretRedactor.arguments(json, object.path("name").asText(),
                    object.path("arguments").asText()));
        }
        return copy;
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
        String productivityJar = productivityJar();
        return String.format(
                "You are a coding agent working in a local repository. Work autonomously toward the user's request.\n"
                        + "Inspect relevant files before changing them. Keep edits focused, preserve existing work, and verify changes.\n"
                        + "Use the provided tools instead of inventing file contents or command results. Never claim to have run a\n"
                        + "command you did not run. File tools are constrained to the workspace. Destructive or command actions may\n"
                        + "require approval. If a tool fails, reason from the error and choose a safe alternative.\n"
                        + "\n"
                        + "Productivity work: the bundled Java 11 library set is expected at: %s\n"
                        + "Verify that file exists before using it; if it is absent, report that the bundle is unavailable.\n"
                        + "Bundled libraries:\n"
                        + "- Apache POI: XLS/XLSX, DOCX, and PPTX reading and writing.\n"
                        + "- PDFBox: PDF reading, writing, rendering, forms, merging, and splitting.\n"
                        + "- Tika Core: file-type detection and metadata; it does not include Tika's full parser package.\n"
                        + "- Commons CSV: CSV, TSV, and custom-delimited text parsing and writing.\n"
                        + "- Jackson: JSON and YAML parsing, generation, trees, and object mapping.\n"
                        + "- jsoup: HTML/XML parsing, CSS selectors, editing, text extraction, and sanitizing.\n"
                        + "- commonmark-java: Markdown parsing and HTML/plain-text/Markdown rendering, with GFM tables.\n"
                        + "- Commons IO: file, directory, and stream utilities.\n"
                        + "- Commons Compress and XZ: ZIP, TAR, gzip, bzip2, 7z, XZ, and LZMA archives/compression.\n"
                        + "- Commons Lang, Text, and Codec: strings, escaping, interpolation, encodings, and hashes.\n"
                        + "- Commons Math: statistics, regression, linear algebra, optimization, and numerical methods.\n"
                        + "- TwelveMonkeys ImageIO: enhanced JPEG, TIFF, BMP, and PSD support through ImageIO.\n"
                        + "- XChart: line, scatter, bar, histogram, pie, heatmap, and box charts with image export.\n"
                        + "Use JShell for Office, PDF, CSV, JSON/YAML, HTML, Markdown, archive, image, chart, text,\n"
                        + "codec, math, and similar document-processing tasks. Prefer writing a reusable .jsh script in the\n"
                        + "workspace, then run `jshell --class-path \"%s\" script.jsh`. The production shell is Windows\n"
                        + "cmd.exe; tests may run in a Linux shell. Quote the JAR and script paths, use the host's path syntax,\n"
                        + "and do not assume Unix commands exist on Windows. For a normal Java source-file script, use\n"
                        + "`java --class-path \"%s\" Script.java`. Do not download dependencies at runtime.\n"
                        + "\n"
                        + "Workspace: %s\n"
                        + "Permission mode: %s\n"
                        + "Current date: %s\n",
                productivityJar, productivityJar, productivityJar, config.workspace(),
                config.permissionMode().name().toLowerCase(java.util.Locale.ROOT), LocalDate.now());
    }

    private static String productivityJar() {
        String configured = System.getenv("JAVA_AGENT_PRODUCTIVITY_JAR");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize().toString();
        try {
            Path location = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = location.getParent();
            Path colocated = directory.resolve("productivity.jar").toAbsolutePath().normalize();
            if (Files.exists(colocated)) return colocated.toString();
            Path project = directory.getFileName().toString().equals("target") ? directory.getParent() : directory;
            Path development = project.resolve("productivity").resolve("target")
                    .resolve("productivity.jar").toAbsolutePath().normalize();
            return Files.exists(development) ? development.toString() : colocated.toString();
        } catch (URISyntaxException | RuntimeException unavailable) {
            return Path.of("productivity.jar").toAbsolutePath().normalize().toString();
        }
    }

    void setToolResultSession(String sessionId) {
        if (resultStore != null) resultStore.setSession(sessionId);
    }

    public static final class ToolCallRecord {
        private final String name;
        private final String status;

        public ToolCallRecord(String name, String status) {
            this.name = name;
            this.status = status;
        }

        public String name() { return name; }
        public String status() { return status; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ToolCallRecord)) return false;
            ToolCallRecord that = (ToolCallRecord) other;
            return Objects.equals(name, that.name) && Objects.equals(status, that.status);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(status);
            return result;
        }

        @Override
        public String toString() {
            return "ToolCallRecord[name=" + name + ", status=" + status + "]";
        }
    }

    interface ParentContext {
        PreparedParentContext prepare() throws IOException;
        void acknowledge(PreparedParentContext prepared) throws IOException;
    }

    static final class PreparedParentContext {
        private final String content;
        private final List<ParentDeliveryAck> acknowledgements;

        PreparedParentContext(String content, List<ParentDeliveryAck> acknowledgements) {
            this.content = content == null ? "" : content;
            this.acknowledgements = List.copyOf(acknowledgements);
        }

        public String content() { return content; }
        public List<ParentDeliveryAck> acknowledgements() { return acknowledgements; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PreparedParentContext)) return false;
            PreparedParentContext that = (PreparedParentContext) other;
            return Objects.equals(content, that.content)
                    && Objects.equals(acknowledgements, that.acknowledgements);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(content);
            result = 31 * result + Objects.hashCode(acknowledgements);
            return result;
        }

        @Override
        public String toString() {
            return "PreparedParentContext[content=" + content + ", acknowledgements="
                    + acknowledgements + "]";
        }
    }

    static final class ParentDeliveryAck {
        private final String childId;
        private final String targetParentId;
        private final long throughSequence;

        ParentDeliveryAck(String childId, String targetParentId, long throughSequence) {
            this.childId = childId;
            this.targetParentId = targetParentId;
            this.throughSequence = throughSequence;
        }

        public String childId() { return childId; }
        public String targetParentId() { return targetParentId; }
        public long throughSequence() { return throughSequence; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ParentDeliveryAck)) return false;
            ParentDeliveryAck that = (ParentDeliveryAck) other;
            return throughSequence == that.throughSequence
                    && Objects.equals(childId, that.childId)
                    && Objects.equals(targetParentId, that.targetParentId);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(childId);
            result = 31 * result + Objects.hashCode(targetParentId);
            result = 31 * result + Long.hashCode(throughSequence);
            return result;
        }

        @Override
        public String toString() {
            return "ParentDeliveryAck[childId=" + childId + ", targetParentId=" + targetParentId
                    + ", throughSequence=" + throughSequence + "]";
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
