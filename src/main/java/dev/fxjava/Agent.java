package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InterruptedIOException;
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
    private PrintStream progress;
    private final int maxSteps;
    private final ToolResultStore resultStore;
    private final ParentContext parentContext;
    private final ContextBudget contextBudget;
    private TurnListener turnListener = TurnListener.NONE;
    private String instructions;
    private long turnInputTokens;
    private long turnOutputTokens;

    /** Observes tool activity within one prompt turn; implementations must not throw. */
    public interface TurnListener {
        void onToolStart(String name, String preview);
        void onToolEnd(String name, boolean error);

        /**
         * Delivered exactly once per prompt turn attempt with cumulative input
         * and output token totals parsed from the Responses usage payload;
         * zeros when the provider omitted usage. Successful turns deliver the
         * full turn; failed turns (step-limit exhaustion, IO errors, or
         * cancellation) finalize whatever the already-completed steps consumed.
         */
        default void onUsage(long inputTokens, long outputTokens) { }

        TurnListener NONE = new TurnListener() {
            @Override public void onToolStart(String name, String preview) { }
            @Override public void onToolEnd(String name, boolean error) { }
        };
    }

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
        this(json, client, tools, approvalPolicy, progress, maxSteps, systemPrompt,
                resultStore, parentContext, new ContextBudget());
    }

    Agent(ObjectMapper json, ResponsesClient client, List<Tool> tools,
          ApprovalPolicy approvalPolicy, PrintStream progress, int maxSteps,
          String systemPrompt, ToolResultStore resultStore, ParentContext parentContext,
          ContextBudget contextBudget) {
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
        this.contextBudget = Objects.requireNonNull(contextBudget, "contextBudget");
    }

    ContextBudget contextBudget() { return contextBudget; }

    long fixedRequestTokens() throws IOException {
        long fixed = contextBudget.estimateTextTokens(instructions)
                + contextBudget.estimateTokens(buildToolDefinitions(toolCatalog));
        if (parentContext != null) {
            PreparedParentContext prepared = parentContext.prepare();
            if (prepared != null && !prepared.content().isBlank()) {
                fixed += contextBudget.estimateTextTokens(prepared.content());
            }
        }
        return fixed;
    }

    public String prompt(String input) throws IOException, InterruptedException {
        return prompt(input, ignored -> { });
    }

    public String prompt(String input, Consumer<String> textDelta)
            throws IOException, InterruptedException {
        return prompt(input, textDelta, TurnListener.NONE);
    }

    public String prompt(String input, Consumer<String> textDelta, TurnListener listener)
            throws IOException, InterruptedException {
        return runTurn(null, input, textDelta, listener, HistoryCheckpoint.NONE);
    }

    String prompt(String input, Consumer<String> textDelta, TurnListener listener,
                  HistoryCheckpoint checkpoint) throws IOException, InterruptedException {
        return runTurn(null, input, textDelta, listener, checkpoint);
    }

    /**
     * Internal seam for pre-built user messages, e.g. multimodal turns whose
     * content is an input_text + input_image array. The message is appended to
     * durable history exactly as given (deep-copied); tool-call pairing,
     * replay, and persistence behave identically to {@link #prompt}.
     */
    String promptWithUserMessage(ObjectNode userMessage, Consumer<String> textDelta,
                                 TurnListener listener, HistoryCheckpoint checkpoint)
            throws IOException, InterruptedException {
        return runTurn(Objects.requireNonNull(userMessage, "userMessage"), null,
                textDelta, listener, checkpoint);
    }

    private String runTurn(ObjectNode prebuiltUserMessage, String textInput,
                           Consumer<String> textDelta, TurnListener listener,
                           HistoryCheckpoint checkpoint)
            throws IOException, InterruptedException {
        lastToolCalls.clear();
        turnListener = listener == null ? TurnListener.NONE : listener;
        turnInputTokens = 0;
        turnOutputTokens = 0;
        ToolExecutionState execution = new ToolExecutionState();
        if (prebuiltUserMessage != null) addUserMessage(prebuiltUserMessage);
        else addUserMessage(textInput);
        try {
            checkpointHistory(checkpoint);
            for (int step = 0; step < maxSteps; step++) {
                PreparedParentContext prepared = parentContext == null ? null : parentContext.prepare();
                ArrayNode definitions = buildToolDefinitions(toolCatalog);
                long fixedRequestTokens = contextBudget.estimateTextTokens(instructions)
                        + contextBudget.estimateTokens(definitions);
                if (prepared != null && !prepared.content().isBlank()) {
                    fixedRequestTokens += contextBudget.estimateTextTokens(prepared.content());
                }
                compactIfNeeded(fixedRequestTokens, checkpoint);

                ArrayNode requestInput = inputHistory.deepCopy();
                // fx projects history-layer summaries into plain messages before
                // any gateway call; the raw item never crosses the wire.
                ConversationCompactor.projectSummariesForWire(requestInput);
                if (prepared != null && !prepared.content().isBlank()) {
                    ObjectNode context = json.createObjectNode().put("role", "system")
                            .put("content", prepared.content());
                    requestInput.insert(0, context);
                }
                checkCancellation("before gateway request");
                ObjectNode response = client.complete(requestInput, definitions, instructions, textDelta);
                long[] usage = OpenAiResponsesClient.parseUsage(response);
                turnInputTokens += usage[0];
                turnOutputTokens += usage[1];
                ArrayNode output = (ArrayNode) response.path("output");
                List<JsonNode> functionCalls = new ArrayList<>();

                for (JsonNode item : output) {
                    inputHistory.add(persistable(item));
                    if (item.path("type").asText().equals("function_call")) functionCalls.add(item);
                }

                // The model response becomes durable before any returned tool
                // call can run. This is the intent checkpoint for mutations.
                checkpointHistory(checkpoint);
                if (prepared != null) parentContext.acknowledge(prepared);
                if (functionCalls.isEmpty()) return extractOutputText(output);
                for (JsonNode call : functionCalls) {
                    execution.begin(call);
                    executeToolCall(call, execution);
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("tool execution was interrupted");
                    }
                    checkpointHistory(checkpoint);
                    execution.clear();
                }
            }
            throw new IOException("Agent stopped after reaching the " + maxSteps + " step limit");
        } catch (InterruptedException interrupted) {
            Thread.interrupted();
            try {
                closePendingToolCalls(execution, true);
                checkpointHistory(checkpoint);
            } catch (IOException | InterruptedException repairFailure) {
                interrupted.addSuppressed(repairFailure);
            } finally {
                Thread.interrupted();
                Thread.currentThread().interrupt();
            }
            throw interrupted;
        } catch (IOException failure) {
            boolean restoreInterrupt = Thread.interrupted();
            try {
                closePendingToolCalls(execution, false);
                checkpointHistory(checkpoint);
            } catch (IOException repairFailure) {
                failure.addSuppressed(repairFailure);
            } catch (InterruptedException repairCancellation) {
                failure.addSuppressed(repairCancellation);
                restoreInterrupt = true;
            } finally {
                boolean interruptedDuringRepair = Thread.interrupted();
                if (restoreInterrupt || interruptedDuringRepair) Thread.currentThread().interrupt();
            }
            throw failure;
        } catch (RuntimeException failure) {
            boolean restoreInterrupt = Thread.interrupted();
            try {
                closePendingToolCalls(execution, false);
                checkpointHistory(checkpoint);
            } catch (Exception repairFailure) {
                failure.addSuppressed(repairFailure);
                if (repairFailure instanceof InterruptedException) restoreInterrupt = true;
            } finally {
                boolean interruptedDuringRepair = Thread.interrupted();
                if (restoreInterrupt || interruptedDuringRepair) Thread.currentThread().interrupt();
            }
            throw failure;
        } finally {
            notifyUsage(turnInputTokens, turnOutputTokens);
        }
    }

    private void compactIfNeeded(long fixedRequestTokens, HistoryCheckpoint checkpoint)
            throws IOException, InterruptedException {
        if (!contextBudget.shouldCompact(inputHistory, fixedRequestTokens)) return;
        checkCancellation("before automatic compaction");
        // Reject irreducible histories before paying for a summary request.
        ConversationCompactor.rebuildForBudget(json, inputHistory, "x", contextBudget, fixedRequestTokens);
        ArrayNode summaryFraming = json.createArrayNode();
        ObjectNode summaryMessage = summaryFraming.addObject().put("role", "user");
        summaryMessage.put("content", "");
        long summaryFixedTokens = contextBudget.estimateTokens(summaryFraming)
                + contextBudget.estimateTextTokens(ConversationCompactor.SUMMARIZER_INSTRUCTIONS) + 32;
        String transcript = ConversationCompactor.renderTranscript(inputHistory, contextBudget, summaryFixedTokens);
        SummarizeResult summarized = summarizeWithUsage(transcript, ConversationCompactor.SUMMARIZER_INSTRUCTIONS);
        turnInputTokens += summarized.inputTokens;
        turnOutputTokens += summarized.outputTokens;
        // Meter the summary request even if its result cannot fit or history save fails.
        checkpointHistory(checkpoint);
        ArrayNode rebuilt = ConversationCompactor.rebuildForBudget(json, inputHistory,
                summarized.summary, contextBudget, fixedRequestTokens);
        checkpointSnapshot(checkpoint, rebuilt);
        inputHistory.removeAll();
        for (JsonNode item : rebuilt) inputHistory.add(item.deepCopy());
    }

    /**
     * One extra non-streaming completion with no tools that never touches the
     * conversation state; the seam behind /compact's summarization round-trip.
     */
    public String summarize(String content, String instructions)
            throws IOException, InterruptedException {
        return summarizeWithUsage(content, instructions).summary;
    }

    /** Same seam as {@link #summarize}, plus the token usage the round-trip consumed. */
    public SummarizeResult summarizeWithUsage(String content, String instructions)
            throws IOException, InterruptedException {
        ArrayNode input = json.createArrayNode();
        ObjectNode message = input.addObject();
        message.put("role", "user");
        message.put("content", content);
        ObjectNode response = client.complete(input, json.createArrayNode(), instructions);
        long[] usage = OpenAiResponsesClient.parseUsage(response);
        return new SummarizeResult(extractOutputText((ArrayNode) response.path("output")),
                usage[0], usage[1]);
    }

    /** Summary text plus the input/output tokens its round-trip consumed. */
    public static final class SummarizeResult {
        public final String summary;
        public final long inputTokens;
        public final long outputTokens;

        public SummarizeResult(String summary, long inputTokens, long outputTokens) {
            this.summary = summary;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        @Override
        public String toString() {
            return "SummarizeResult[summary=" + summary + ", input=" + inputTokens
                    + ", output=" + outputTokens + "]";
        }
    }

    public ArrayNode snapshotInput() {
        return inputHistory.deepCopy();
    }

    /** UI seam for redirecting the [tool] progress line on interactive paths. */
    void setProgress(PrintStream progress) {
        this.progress = progress;
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
            output.put("output", "Error: previous tool call may have been interrupted; its outcome is uncertain, "
                    + "and it will not be replayed");
        }
    }

    private void executeToolCall(JsonNode call, ToolExecutionState execution)
            throws IOException, InterruptedException {
        String callId = call.path("call_id").asText();
        String name = call.path("name").asText();
        String rawArguments = call.path("arguments").asText("{}");
        if (callId.isBlank()) throw new IOException("OpenAI returned a function call without call_id");

        ToolResult result;
        boolean policyDenied = false;
        Tool tool = resolveTool(name);
        if (tool == null || !tool.advertised()) {
            result = ToolResult.error("Error: unknown tool '" + name + "'");
        } else {
            try {
                checkCancellation("before tool execution");
                JsonNode arguments = json.readTree(rawArguments);
                if (arguments == null || !arguments.isObject()) {
                    throw new IllegalArgumentException("tool arguments must be a JSON object");
                }
                String preview = ToolPreview.safeText(tool.preview(arguments));
                progress.println("[tool] " + preview);
                notifyToolStart(name, preview);
                execution.started = true;
                checkCancellation("before tool execution");
                if (approvalPolicy.preflightDeny(tool, arguments)
                        || (tool.requiresApproval(arguments) && !approvalPolicy.approve(tool, arguments))) {
                    policyDenied = true;
                    result = ToolResult.error("Error: user denied this tool call");
                } else {
                    checkCancellation("before tool execution");
                    execution.invoked = true;
                    result = Objects.requireNonNull(tool.executeResult(arguments, callId),
                            "tool returned no outcome");
                }
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception error) {
                if (error instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                    InterruptedException interrupted = new InterruptedException("tool execution was interrupted");
                    interrupted.initCause(error);
                    throw interrupted;
                }
                result = ToolResult.error("Error: " + safeMessage(error));
            }
        }

        boolean toolError = policyDenied || tool == null || result.isError();
        String output = result.output();
        if (resultStore != null && !name.equals("read_tool_result")) {
            output = resultStore.prepare(callId, name, output);
        }

        lastToolCalls.add(new ToolCallRecord(name, toolError ? "error" : "success"));
        notifyToolEnd(name, toolError);
        ObjectNode toolOutput = inputHistory.addObject();
        toolOutput.put("type", "function_call_output");
        toolOutput.put("call_id", callId);
        toolOutput.put("output", output);
    }

    private void checkpointHistory(HistoryCheckpoint checkpoint)
            throws IOException, InterruptedException {
        checkpointSnapshot(checkpoint, inputHistory);
    }

    private void checkpointSnapshot(HistoryCheckpoint checkpoint, ArrayNode history)
            throws IOException, InterruptedException {
        checkCancellation("before history checkpoint");
        if (checkpoint != null) {
            try {
                checkpoint.checkpoint(history.deepCopy(), turnInputTokens, turnOutputTokens);
            } catch (RuntimeException failure) {
                throw new IOException("could not checkpoint the active conversation", failure);
            }
        }
    }

    private boolean closePendingToolCalls(ToolExecutionState execution, boolean interrupted) throws IOException {
        Map<String, JsonNode> pending = new java.util.LinkedHashMap<>();
        for (JsonNode item : inputHistory) {
            String id = item.path("call_id").asText();
            if (id.isBlank()) continue;
            String type = item.path("type").asText();
            if (type.equals("function_call")) pending.putIfAbsent(id, item);
            else if (type.equals("function_call_output")) pending.remove(id);
        }
        if (pending.isEmpty()) {
            execution.clear();
            return false;
        }
        for (Map.Entry<String, JsonNode> entry : pending.entrySet()) {
            String id = entry.getKey();
            String name = entry.getValue().path("name").asText("unknown tool");
            boolean active = id.equals(execution.callId);
            boolean started = active && execution.started;
            String detail;
            if (active && execution.invoked && interrupted) {
                detail = "Error: tool execution was interrupted; the outcome is uncertain and will not be retried";
            } else if (active && execution.invoked) {
                detail = "Error: tool execution stopped before its result was checkpointed; the outcome is uncertain "
                        + "and will not be retried";
            } else {
                detail = "Error: tool call was not started because the turn stopped; it will not be replayed";
            }
            appendToolOutput(id, name, detail);
            if (started) notifyToolEnd(name, true);
        }
        execution.clear();
        return true;
    }

    private void appendToolOutput(String callId, String name, String output) throws IOException {
        String saved = resultStore == null || name.equals("read_tool_result")
                ? output : resultStore.prepare(callId, name, output);
        lastToolCalls.add(new ToolCallRecord(name, "error"));
        ObjectNode toolOutput = inputHistory.addObject();
        toolOutput.put("type", "function_call_output");
        toolOutput.put("call_id", callId);
        toolOutput.put("output", saved);
    }

    private static void checkCancellation(String stage) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("turn interrupted " + stage);
    }

    private void notifyToolStart(String name, String preview) {
        try { turnListener.onToolStart(name, preview); } catch (RuntimeException ignored) { }
    }

    private void notifyToolEnd(String name, boolean error) {
        try { turnListener.onToolEnd(name, error); } catch (RuntimeException ignored) { }
    }

    private void notifyUsage(long input, long output) {
        try { turnListener.onUsage(input, output); } catch (RuntimeException ignored) { }
    }

    @FunctionalInterface
    interface HistoryCheckpoint {
        void checkpoint(ArrayNode history, long inputTokens, long outputTokens) throws IOException;

        HistoryCheckpoint NONE = (history, input, output) -> { };
    }

    private static final class ToolExecutionState {
        private String callId;
        private boolean started;
        private boolean invoked;

        void begin(JsonNode call) {
            callId = call.path("call_id").asText();
            started = false;
            invoked = false;
        }

        void clear() {
            callId = null;
            started = false;
            invoked = false;
        }
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

    /** Appends a caller-built user message item (deep copy) to durable history. */
    private void addUserMessage(ObjectNode message) {
        inputHistory.add(message.deepCopy());
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
                        + "For artifact-producing Office, PDF, CSV, JSON/YAML, HTML, Markdown, archive, image, chart,\n"
                        + "text, codec, math, and similar tasks, prefer a reusable Java source-file program. Uncaught\n"
                        + "exceptions produce a failing process status; after writing an artifact, reopen it and assert its\n"
                        + "contents or structure. Run it with `java --class-path \"%s\" Script.java`. Use JShell for\n"
                        + "exploration, or only when the snippet explicitly reports failures and validates its outputs. The\n"
                        + "production shell is Windows\n"
                        + "cmd.exe; tests may run in a Linux shell. Quote the JAR and script paths, use the host's path syntax,\n"
                        + "and do not assume Unix commands exist on Windows. Do not download dependencies at runtime.\n"
                        + "\n"
                        + "Workspace: %s\n"
                        + "Permission mode: %s\n"
                        + "Current date: %s\n",
                productivityJar, productivityJar, config.workspace(),
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
