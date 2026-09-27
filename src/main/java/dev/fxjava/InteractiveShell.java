package dev.fxjava;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw-mode interactive shell: styled prompt, in-place editing, draft-preserving
 * history recall, bracketed paste, Ctrl+C cancel/exit semantics, slash popup
 * menu, status hint row, inline approvals, inline ask_user panels, and bounded
 * type-ahead replay. Rendering goes through {@link Ansi}; every key action
 * funnels through {@link #handle}.
 */
final class InteractiveShell implements QuestionFlow {
    private static final long CTRL_C_EXIT_WINDOW_NANOS = 1_500_000_000L;
    private static final long SPINNER_INTERVAL_NANOS = 100_000_000L;
    private static final long SIZE_REFRESH_INTERVAL_NANOS = 250_000_000L;
    private static final int TYPE_AHEAD_CAPACITY_CHARS = 4096;
    private static final int LINE_INPUT_LIMIT_CHARS = 256;

    private final SessionRuntime session;
    private final AgentConfig config;
    private final String systemPrompt;
    private final McpRuntime mcp;
    private final Path sessionRoot;
    private final InputStream input;
    private final PrintStream out;
    private final PrintStream error;
    private final Ansi ansi;
    private final ApprovalRouter approvalRouter;
    private String modelSource;
    private String effortSource = "default";
    private final Spinner.Clock clock;

    private final Composer composer = new Composer();
    private final KeyDecoder decoder = new KeyDecoder();
    private final SlashMenu menu = new SlashMenu();
    private final TypeAheadQueue typeAhead = new TypeAheadQueue(TYPE_AHEAD_CAPACITY_CHARS);
    private final RefreshGate sizeGate;
    private PromptHistory history;
    private List<String> historyEntries = List.of();
    private int historyIndex;
    private String historyDraft = "";
    private long ctrlCArmedAt;
    private boolean running = true;
    private boolean menuDismissed;
    private int renderedRows;
    private int cursorRowFromTop;
    private Thread worker;
    private TranscriptPresenter activePresenter;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private TerminalCapabilities capabilities;
    private int columns = TerminalCapabilities.Size.FALLBACK.columns;

    InteractiveShell(SessionRuntime session, AgentConfig config, String systemPrompt,
                     McpRuntime mcp, Path sessionRoot, InputStream input,
                     PrintStream out, PrintStream error, Ansi ansi,
                     ApprovalRouter approvalRouter, String modelSource, Spinner.Clock clock) {
        this(session, config, systemPrompt, mcp, sessionRoot, input, out, error, ansi,
                approvalRouter, modelSource, "default", clock);
    }

    InteractiveShell(SessionRuntime session, AgentConfig config, String systemPrompt,
                     McpRuntime mcp, Path sessionRoot, InputStream input,
                     PrintStream out, PrintStream error, Ansi ansi,
                     ApprovalRouter approvalRouter, String modelSource, String effortSource,
                     Spinner.Clock clock) {
        this.session = session;
        this.config = config;
        this.systemPrompt = systemPrompt;
        this.mcp = mcp;
        this.sessionRoot = sessionRoot;
        this.input = input;
        this.out = out;
        this.error = error;
        this.ansi = ansi;
        this.approvalRouter = approvalRouter;
        this.modelSource = modelSource;
        this.effortSource = effortSource;
        this.clock = clock;
        this.sizeGate = new RefreshGate(clock, SIZE_REFRESH_INTERVAL_NANOS);
    }

    int run(RawTerminal terminal, TerminalCapabilities detected) throws Exception {
        capabilities = detected;
        refreshColumns(true);
        history = new PromptHistory(sessionRoot);
        historyEntries = history.entries();
        historyIndex = historyEntries.size();
        out.print(ansi.bracketedPaste(true));
        printWelcome();
        out.flush();
        approvalRouter.attach(new RawChannel());
        try {
            while (running) {
                render();
                if (!pumpInput()) break;
            }
        } finally {
            approvalRouter.detach();
            eraseRendered();
            out.print(ansi.bracketedPaste(false));
            out.println();
            out.flush();
            interruptWorker();
        }
        return 0;
    }

    /** Reads one chunk of terminal bytes and dispatches decoded events; false on EOF. */
    private boolean pumpInput() throws IOException, InterruptedException {
        byte[] buffer = new byte[1024];
        int count = input.read(buffer);
        if (count < 0) return false;
        List<KeyEvent> events = decoder.feed(buffer, count);
        events.addAll(decoder.flushPending());
        for (KeyEvent event : events) handle(event);
        return running;
    }

    /** Central key dispatch: composer edits, menus, and history actions live here. */
    private void handle(KeyEvent event) throws IOException, InterruptedException {
        switch (event.kind()) {
            case TEXT:
                composer.insert(event.text());
                afterEdit();
                break;
            case ENTER:
                SlashCommands.Match selected = menu.selectedMatch();
                if (selected != null && !selected.token.equals(composer.text())) selectCompletion();
                else submit(composer.text());
                break;
            case BACKSPACE:
                composer.backspace();
                afterEdit();
                break;
            case DELETE:
                composer.deleteForward();
                afterEdit();
                break;
            case LEFT:
                composer.left();
                afterEdit();
                break;
            case RIGHT:
                composer.right();
                afterEdit();
                break;
            case HOME:
                composer.moveToLineStart();
                afterEdit();
                break;
            case END:
                composer.moveToLineEnd();
                afterEdit();
                break;
            case UP:
                if (menu.active()) menu.move(-1);
                else historyOrVertical(true);
                break;
            case DOWN:
                if (menu.active()) menu.move(1);
                else historyOrVertical(false);
                break;
            case TAB:
                if (menu.active()) menu.move(1);
                break;
            case SHIFT_TAB:
                if (menu.active()) menu.move(-1);
                break;
            case ALT_CHAR:
                handleAlt(event.key());
                break;
            case CTRL_CHAR:
                handleControl(event.key());
                break;
            case ESCAPE:
                if (menu.active()) {
                    menuDismissed = true;
                    refreshMenu();
                } else {
                    ctrlCArmedAt = 0;
                }
                break;
            case PASTE_START:
                ctrlCArmedAt = 0;
                break;
            case PASTE_END:
            case PAGE_UP:
            case PAGE_DOWN:
            case UNKNOWN:
            default:
                // paging and exotic sequences stay inert
                break;
        }
    }

    private void handleAlt(char key) {
        if (key == 'b') composer.moveWordLeft();
        else if (key == 'f') composer.moveWordRight();
        else if (key == 'd') composer.killWordForward();
        else if (key == '\u007f') composer.killWordBack();
        afterEdit();
    }

    private void handleControl(char key) throws IOException, InterruptedException {
        switch (key) {
            case 'a':
                composer.moveToLineStart();
                break;
            case 'e':
                composer.moveToLineEnd();
                break;
            case 'b':
                composer.left();
                break;
            case 'f':
                composer.right();
                break;
            case 'k':
                composer.killToEndOfLine();
                break;
            case 'u':
                composer.killToLineStart();
                break;
            case 'w':
                composer.killWordBack();
                break;
            case 'n':
                recallHistory(false);
                break;
            case 'p':
                recallHistory(true);
                break;
            case 'd':
                if (composer.isEmpty()) {
                    running = false;
                    return;
                }
                composer.deleteForward();
                break;
            case 'l':
                out.print(ansi.clearScreen());
                refreshColumns(true);
                cursorRowFromTop = 0;
                markEdited();
                return;
            case 'c':
                handleCtrlC();
                return;
            default:
                // Ctrl+R search, Ctrl+T transpose: later milestones
                break;
        }
        afterEdit();
    }

    /**
     * First press cancels an in-flight generation; otherwise it arms exit.
     * A second press within the window leaves the shell.
     */
    private void handleCtrlC() throws InterruptedException {
        if (worker != null && worker.isAlive()) {
            cancelled.set(true);
            TranscriptPresenter presenter = activePresenter;
            if (presenter != null) presenter.cancel();
            worker.interrupt();
            out.println("^C cancelled");
            out.flush();
            worker.join(10_000);
            ctrlCArmedAt = 0;
            return;
        }
        long now = System.nanoTime();
        if (ctrlCArmedAt != 0 && now - ctrlCArmedAt <= CTRL_C_EXIT_WINDOW_NANOS) {
            running = false;
            return;
        }
        ctrlCArmedAt = now;
        composer.clear();
        afterEdit();
    }

    /** Re-evaluates the popup after any edit; programmatic dismissal clears on the next edit. */
    private void afterEdit() {
        menuDismissed = false;
        markEdited();
        refreshMenu();
    }

    private void refreshMenu() {
        String text = composer.text();
        boolean eligible = !menuDismissed && text.length() > 0 && text.charAt(0) == '/'
                && text.indexOf(' ') < 0 && text.indexOf('\t') < 0 && text.indexOf('\n') < 0;
        menu.sync(eligible, text);
    }

    /** Completes the selected command into the composer without submitting. */
    private void selectCompletion() {
        SlashCommands.Match match = menu.selectedMatch();
        if (match == null) return;
        composer.setText(match.token);
        menuDismissed = true;
        refreshMenu();
    }

    private void submit(String line) throws IOException, InterruptedException {
        eraseRendered();
        out.print(promptStyled());
        out.println(line.replace("\n", " ⏎ "));
        out.flush();
        composer.clear();
        renderedRows = 0;
        cursorRowFromTop = 0;
        if (!line.isBlank()) dispatch(line.strip());
    }

    void dispatch(String line) throws IOException, InterruptedException {
        boolean persistedCommand = line.equals("/new") || line.equals("/sessions")
                || line.equals("/resume") || line.startsWith("/resume ")
                || line.startsWith("/recover ") || line.startsWith("/rename ");
        if (persistedCommand && !session.persistent()) {
            out.println("Session persistence is disabled by --no-save.");
            resetHistoryNavigation();
            return;
        }
        SlashCommands.Spec spec = line.startsWith("/") ? SlashCommands.resolve(line) : null;
        if (spec == null && line.startsWith("/")) {
            out.println("Unknown command " + firstToken(line) + ", try /help.");
            resetHistoryNavigation();
            return;
        }
        if (spec == null) {
            generate(line);
            recordHistory(line);
            resetHistoryNavigation();
            return;
        }
        switch (spec.command) {
            case "/help":
                out.print(SlashCommands.catalog(argumentAfter(line, "/help"), columns, ansi));
                out.flush();
                break;
            case "/clear":
                session.clear(systemPrompt);
                out.println("Conversation cleared.");
                break;
            case "/new":
                session.newSession(config.workspace(), config.model(), systemPrompt);
                approvalRouter.clearSessionGrants();
                out.println("New session: " + session.id());
                break;
            case "/resume": {
                String id = argumentAfter(line, "/resume");
                String selectedModel = session.model();
                boolean keepConfiguredModel = modelSourceWinsOverSession(modelSource);
                session.resume(id.isEmpty() ? "last" : id, config.workspace());
                if (keepConfiguredModel) session.setModel(selectedModel);
                else modelSource = "saved session";
                approvalRouter.clearSessionGrants();
                out.println("Resumed session: " + session.id());
                break;
            }
            case "/recover": {
                String selectedModel = session.model();
                boolean keepConfiguredModel = modelSourceWinsOverSession(modelSource);
                session.recover(argumentAfter(line, "/recover"), config.workspace());
                if (keepConfiguredModel) session.setModel(selectedModel);
                else modelSource = "saved session";
                approvalRouter.clearSessionGrants();
                out.println("Recovered as: " + session.id());
                break;
            }
            case "/sessions":
                listSessions();
                break;
            case "/rename":
                session.rename(line.substring("/rename ".length()));
                out.println("Session renamed.");
                break;
            case "/model":
                modelSource = RuntimeSettingCommands.model(session, argumentAfter(line, "/model"),
                        sessionRoot, modelSource, out, error);
                break;
            case "/effort":
                effortSource = RuntimeSettingCommands.effort(session, argumentAfter(line, "/effort"),
                        sessionRoot, effortSource, out, error);
                break;
            case "/permissions":
                PermissionCommands.handle(session, argumentAfter(line, "/permissions"),
                        modeLabel(), approvalRouter.grantCount(), ansi, out,
                        approvalRouter::setPermissionMode);
                break;
            case "/status":
                printStatus();
                break;
            case "/stats":
                StatsCommands.handle(session, argumentAfter(line, "/stats"), config.workspace(),
                        ansi, out);
                break;
            case "/compact":
                compact(argumentAfter(line, "/compact"));
                break;
            case "/image":
                ImageCommands.handle(session, argumentAfter(line, "/image"),
                        config.workspace(), ansi, out);
                break;
            case "/mcp":
                mcpHealth(argumentAfter(line, "/mcp"));
                break;
            case "/exit":
                running = false;
                break;
            default:
                break;
        }
        resetHistoryNavigation();
    }

    private void mcpHealth(String argument) throws IOException {
        if (!argument.isEmpty() && !argument.equals("list") && !argument.equals("status")) {
            out.println("Usage: /mcp [list|status]");
            return;
        }
        out.print(mcp.healthText());
    }

    private void printStatus() {
        out.println("Workspace: " + config.workspace());
        out.println("Model: " + session.model() + " · source: " + modelSource);
        String effort = session.reasoningEffort();
        out.println("Reasoning effort: " + (effort == null ? "provider default" : effort)
                + " · source: " + effortSource);
        out.println("Permission mode: " + modeLabel());
        out.println("Session: " + (session.id() == null ? "unsaved (--no-save)" : session.id()));
    }

    private String modeLabel() {
        PermissionMode active = approvalRouter.permissionMode();
        return (active == null ? config.permissionMode() : active).name().toLowerCase(Locale.ROOT);
    }

    private static boolean modelSourceWinsOverSession(String source) {
        return source.equals("--model flag") || source.startsWith("env ")
                || source.equals("saved preference");
    }

    /** Text after the command token; empty when the command was typed bare. */
    private static String argumentAfter(String line, String command) {
        if (line.length() <= command.length()) return "";
        return line.substring(command.length() + 1).trim();
    }

    private static String firstToken(String line) {
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }

    private void listSessions() throws IOException {
        List<SessionStore.Snapshot> snapshots = session.sessions(config.workspace(), 20);
        if (snapshots.isEmpty()) out.println("No saved sessions.");
        for (SessionStore.Snapshot saved : snapshots) {
            String current = saved.id().equals(session.id()) ? " *" : "";
            String title = saved.title().isBlank() ? "" : "  " + saved.title();
            out.println(saved.id() + current + title);
        }
    }

    /** Runs one agent turn on a worker thread while the loop keeps watching for keys. */
    private void generate(String prompt) throws IOException, InterruptedException {
        cancelled.set(false);
        refreshColumns(false);
        List<ImageAttachment> attached = session.pendingImages();
        if (!attached.isEmpty()) {
            out.println(ansi.dim() + ImageCommands.attachmentSummary(attached) + ansi.reset());
            out.flush();
        }
        Spinner spinner = new Spinner(out, ansi, clock, SPINNER_INTERVAL_NANOS);
        TranscriptPresenter presenter = new TranscriptPresenter(out, ansi, columns, spinner);
        AtomicBoolean streamed = new AtomicBoolean();
        AtomicReference<String> answer = new AtomicReference<>("");
        AtomicReference<IOException> failure = new AtomicReference<>();
        long[] turnUsage = new long[2];
        Thread generator = new Thread(() -> {
            try {
                String result = session.prompt(prompt, delta -> {
                    streamed.set(true);
                    presenter.onDelta(delta);
                }, new Agent.TurnListener() {
                    @Override public void onToolStart(String name, String preview) {
                        presenter.onToolStart(name, preview);
                    }

                    @Override public void onToolEnd(String name, boolean error) {
                        presenter.onToolEnd(name, error);
                    }

                    @Override public void onUsage(long inputTokens, long outputTokens) {
                        turnUsage[0] += inputTokens;
                        turnUsage[1] += outputTokens;
                    }
                });
                answer.set(result);
            } catch (InterruptedException interrupted) {
                cancelled.set(true);
                Thread.currentThread().interrupt();
            } catch (IOException failed) {
                failure.set(failed);
            }
        }, "agent-generation");
        worker = generator;
        activePresenter = presenter;
        session.setToolProgress(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        try {
            presenter.begin();
            generator.start();
            watchDuringGeneration(generator, presenter);
            generator.join();
        } finally {
            session.setToolProgress(error);
            activePresenter = null;
            worker = null;
            presenter.finish();
        }
        String queued = typeAhead.drain();
        if (!queued.isEmpty()) composer.insert(queued);
        if (failure.get() != null) {
            error.println("java-agent: " + failure.get().getMessage());
            return;
        }
        if (cancelled.get()) return;
        printTurnUsage(turnUsage[0], turnUsage[1]);
        if (streamed.get()) return;
        if (!answer.get().isBlank()) out.println(new MarkdownConsole(ansi).render(answer.get(), columns));
    }

    /** Dim per-turn totals line; printed only after the spinner is fully erased. */
    private void printTurnUsage(long inputTokens, long outputTokens) {
        out.println(ansi.dim() + "tokens: " + StatsCommands.format(inputTokens, outputTokens)
                + ansi.reset());
        out.flush();
    }

    /**
     * /compact runs the same worker-thread pattern as generation so Ctrl+C
     * interrupts the summarization call mid-flight; the conversation only
     * changes after the round-trip and its immediate persistence succeed.
     */
    private void compact(String argument) throws IOException, InterruptedException {
        if (!argument.isEmpty()) {
            out.println(CompactCommands.USAGE);
            return;
        }
        ArrayNode input = session.conversation();
        if (!ConversationCompactor.eligible(input)) {
            out.println(CompactCommands.refusalLine(input));
            return;
        }
        if (!session.persistent()) {
            out.println(ansi.dim()
                    + "Session persistence is disabled by --no-save; compacting in memory only."
                    + ansi.reset());
        }
        cancelled.set(false);
        refreshColumns(false);
        Spinner spinner = new Spinner(out, ansi, clock, SPINNER_INTERVAL_NANOS);
        TranscriptPresenter presenter = new TranscriptPresenter(out, ansi, columns, spinner);
        AtomicReference<CompactCommands.Result> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread compactor = new Thread(() -> {
            try {
                result.set(CompactCommands.run(session, ""));
            } catch (InterruptedException interrupted) {
                cancelled.set(true);
                Thread.currentThread().interrupt();
            } catch (Throwable failed) {
                // Any unexpected error lands in the friendly failure slot
                // instead of killing the worker (and with it the shell).
                failure.set(failed);
            }
        }, "conversation-compaction");
        worker = compactor;
        activePresenter = presenter;
        try {
            presenter.begin();
            compactor.start();
            watchDuringGeneration(compactor, presenter);
            compactor.join();
        } finally {
            activePresenter = null;
            worker = null;
            presenter.finish();
        }
        Throwable failed = failure.get();
        if (failed != null) {
            error.println("java-agent: compaction failed: " + describe(failed));
            return;
        }
        if (cancelled.get()) {
            out.println("Compaction cancelled; conversation untouched.");
            return;
        }
        out.println(ansi.dim() + CompactCommands.confirmation(result.get()) + ansi.reset());
    }

    private static String describe(Throwable failed) {
        String message = failed.getMessage();
        return message == null || message.isBlank() ? failed.getClass().getSimpleName() : message;
    }

    /**
     * Polls stdin while generation runs: Ctrl+C interrupts the worker, printable
     * text queues for replay, and pending approval or question requests are
     * served inline through the raw stream the worker itself never touches.
     */
    private void watchDuringGeneration(Thread generator, TranscriptPresenter presenter)
            throws InterruptedException, IOException {
        byte[] buffer = new byte[64];
        KeyDecoder generationDecoder = new KeyDecoder();
        while (generator.isAlive()) {
            ApprovalRouter.Request request = approvalRouter.poll();
            if (request != null) serveRequest(request, presenter);
            if (!generator.isAlive()) break;
            int available = input.available();
            if (available <= 0) {
                presenter.tick();
                generator.join(20);
                continue;
            }
            int read = input.read(buffer, 0, Math.min(available, buffer.length));
            if (read < 0) break;
            for (KeyEvent event : generationDecoder.feed(buffer, read)) {
                if (event.kind() == KeyEvent.Kind.CTRL_CHAR && event.key() == 'c') handleCtrlC();
                else if (event.kind() == KeyEvent.Kind.TEXT) typeAhead.offer(event.text());
            }
        }
    }

    /** Serves one worker request on the main loop, then hands the reply back. */
    private void serveRequest(ApprovalRouter.Request request, TranscriptPresenter presenter)
            throws IOException, InterruptedException {
        ApprovalRouter.Channel channel = approvalRouter.channel();
        if (channel == null) {
            request.complete(ApprovalRouter.CANCELLED);
            return;
        }
        presenter.suspendSpinner();
        String reply;
        if (request.question != null) {
            reply = ask(request.question);
        } else {
            reply = request.lineInput
                    ? channel.serveLineInput()
                    : channel.serveApproval(request.tool, request.preview);
        }
        request.complete(reply == null ? ApprovalRouter.CANCELLED : reply);
    }

    private ApprovalPrompt.Decision readDecisionKey() throws IOException, InterruptedException {
        byte[] buffer = new byte[16];
        KeyDecoder decisionDecoder = new KeyDecoder();
        while (true) {
            int read = input.read(buffer);
            if (read < 0) return ApprovalPrompt.Decision.NO;
            for (KeyEvent event : decisionDecoder.feed(buffer, read)) {
                if (event.kind() == KeyEvent.Kind.CTRL_CHAR && event.key() == 'c') {
                    handleCtrlC();
                    return ApprovalPrompt.Decision.NO;
                }
                ApprovalPrompt.Decision decision = ApprovalPrompt.decisionOf(event);
                if (decision != null) return decision;
            }
        }
    }

    /**
     * Inline ask_user_question panel: bold question, numbered options, digits
     * or label prefixes as answers, one dim re-prompt, then the cancel marker
     * per the tool's existing cancelled semantics.
     */
    @Override
    public String ask(AskUserTool.Question question) throws IOException {
        try {
            return QuestionUi.choose(out, ansi, columns, question, this::readAnswerLine);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Serves ask_user_question line input through the raw stream with local echo. */
    private String serveLineInput() throws IOException, InterruptedException {
        out.print('\n');
        out.flush();
        String answer = readAnswerLine();
        return answer == null ? ApprovalRouter.CANCELLED : answer;
    }

    /** Reads one echoed raw-mode line; null on Esc, Ctrl+C, or EOF. */
    private String readAnswerLine() throws IOException, InterruptedException {
        StringBuilder line = new StringBuilder();
        byte[] buffer = new byte[32];
        KeyDecoder lineDecoder = new KeyDecoder();
        String answer = null;
        boolean done = false;
        while (!done) {
            int read = input.read(buffer);
            if (read < 0) break;
            for (KeyEvent event : lineDecoder.feed(buffer, read)) {
                switch (event.kind()) {
                    case ENTER:
                        answer = line.toString();
                        done = true;
                        break;
                    case TEXT:
                        if (line.length() < LINE_INPUT_LIMIT_CHARS) {
                            line.append(event.text());
                            out.print(event.text());
                            out.flush();
                        }
                        break;
                    case BACKSPACE:
                        if (line.length() > 0) {
                            line.deleteCharAt(line.length() - 1);
                            out.print("\b \b");
                            out.flush();
                        }
                        break;
                    case ESCAPE:
                        done = true;
                        break;
                    case CTRL_CHAR:
                        if (event.key() == 'c') {
                            handleCtrlC();
                            done = true;
                        }
                        break;
                    default:
                        break;
                }
                if (done) break;
            }
        }
        out.println();
        out.flush();
        return answer;
    }

    private final class RawChannel implements ApprovalRouter.Channel {
        @Override
        public String serveApproval(String tool, String preview)
                throws IOException, InterruptedException {
            ApprovalPrompt.Box box = ApprovalPrompt.render(out, ansi, columns, tool, preview);
            out.flush();
            ApprovalPrompt.Decision decision = readDecisionKey();
            ApprovalPrompt.erase(out, ansi, box);
            out.flush();
            return decision.reply();
        }

        @Override
        public String serveLineInput() throws IOException, InterruptedException {
            return InteractiveShell.this.serveLineInput();
        }
    }

    private void interruptWorker() {
        Thread active = worker;
        if (active != null && active.isAlive()) active.interrupt();
    }

    private void recordHistory(String line) {
        if (history == null) return;
        history.add(line);
        historyEntries = history.entries();
        try {
            history.save();
        } catch (IOException ignored) {
            // History persistence is best-effort.
        }
    }

    private void resetHistoryNavigation() {
        historyIndex = historyEntries.size();
        historyDraft = "";
    }

    private void markEdited() {
        // Edits keep the recall anchor; the draft snapshot is captured on first Up.
    }

    /** Up/Down walk history at the top/bottom edge, else move within multi-line drafts. */
    private void historyOrVertical(boolean up) {
        VisualLayout layout = VisualLayout.of(composer.text(), columns);
        VisualLayout.Position position = layout.cursorAt(composer.cursor());
        boolean atEdge = up ? position.row() == 0 : position.row() == layout.totalRows() - 1;
        if (!atEdge) {
            int targetRow = position.row() + (up ? -1 : 1);
            composer.moveTo(layout.offsetAt(targetRow, position.column()));
            return;
        }
        recallHistory(up);
    }

    private void recallHistory(boolean older) {
        if (historyEntries.isEmpty()) return;
        if (older) {
            if (historyIndex == historyEntries.size()) historyDraft = composer.text();
            if (historyIndex == 0) return;
            historyIndex--;
            composer.setText(historyEntries.get(historyIndex));
            afterEdit();
        } else {
            if (historyIndex >= historyEntries.size()) return;
            historyIndex++;
            composer.setText(historyIndex == historyEntries.size()
                    ? historyDraft : historyEntries.get(historyIndex));
            afterEdit();
        }
    }

    /**
     * One frame: optional status hint, optional slash menu, then the composer
     * rows; cursor returns inside the composer. Overlay rows erase cleanly
     * because the block top is tracked exactly via {@link #cursorRowFromTop}.
     */
    private void render() {
        refreshColumns(false);
        VisualLayout layout = VisualLayout.of(composer.text(), columns);
        VisualLayout.Position cursor = layout.cursorAt(composer.cursor());
        List<VisualLayout.Row> rows = layout.rows();
        String hint = StatusLines.hint(config.model(), modeLabel(), session.id(),
                Math.max(1, columns), ansi);
        List<String> menuRows = menu.active() ? composeMenuRows() : List.<String>of();
        List<String> pendingRows = composePendingRows();
        StringBuilder frame = new StringBuilder();
        frame.append(ansi.cursorUp(cursorRowFromTop));
        if (!hint.isEmpty()) frame.append('\r').append(ansi.eraseLine()).append(hint).append('\n');
        for (String row : menuRows) {
            frame.append('\r').append(ansi.eraseLine()).append(row).append('\n');
        }
        for (String row : pendingRows) {
            frame.append('\r').append(ansi.eraseLine()).append(row).append('\n');
        }
        String text = composer.text();
        for (int index = 0; index < rows.size(); index++) {
            frame.append('\r').append(ansi.eraseLine());
            if (index == 0) frame.append(promptStyled());
            frame.append(text, rows.get(index).startOffset, trailingBoundary(text, rows.get(index)));
            if (index < rows.size() - 1) frame.append('\n');
        }
        int totalRows = rows.size() + menuRows.size() + pendingRows.size() + (hint.isEmpty() ? 0 : 1);
        int upToCursor = rows.size() - 1 - cursor.row();
        if (renderedRows > totalRows) {
            // The frame shrank (menu closed, text unwrapped): erase the stale tail.
            int surplus = renderedRows - totalRows;
            for (int index = 0; index < surplus; index++) {
                frame.append('\n').append('\r').append(ansi.eraseLine());
            }
            frame.append(ansi.cursorUp(surplus + upToCursor));
        } else {
            frame.append(ansi.cursorUp(upToCursor));
        }
        frame.append('\r')
                .append(ansi.cursorForward(Math.min(columns - 1,
                        VisualLayout.PROMPT_CELLS + cursor.column())));
        out.print(frame);
        out.flush();
        renderedRows = totalRows;
        cursorRowFromTop = totalRows - rows.size() + cursor.row();
    }

    /** One dim pending line per staged attachment, rendered between menu and composer. */
    private List<String> composePendingRows() {
        List<ImageAttachment> pending = session.pendingImages();
        if (pending.isEmpty()) return List.of();
        List<String> rows = new ArrayList<>();
        for (ImageAttachment image : pending) {
            rows.add(ansi.dim() + ImageCommands.pendingLine(image) + ansi.reset());
        }
        return rows;
    }

    /** Two-column menu rows: padded command plus dim description, selected row bolded. */
    private List<String> composeMenuRows() {
        List<SlashCommands.Match> matches = menu.matches();
        int labelWidth = 0;
        for (SlashCommands.Match match : matches) {
            labelWidth = Math.max(labelWidth, MarkdownConsole.visibleWidth(match.token));
        }
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            SlashCommands.Match match = matches.get(index);
            String label = padTo(match.token, labelWidth);
            int budget = columns - 2 - labelWidth - 1;
            String description = ToolGroupLines.truncate(match.spec.description, Math.max(0, budget));
            String row = "  " + label;
            if (!description.isEmpty()) row += " " + description;
            rows.add(index == menu.selectedIndex()
                    ? ansi.bold() + row + ansi.reset()
                    : ansi.dim() + row + ansi.reset());
        }
        return rows;
    }

    private static String padTo(String value, int width) {
        StringBuilder out = new StringBuilder(value);
        int padding = width - MarkdownConsole.visibleWidth(value);
        for (int index = 0; index < padding; index++) out.append(' ');
        return out.toString();
    }

    /** Hanging spaces past the margin are left unrendered to avoid pending-wrap artifacts. */
    private static int trailingBoundary(String text, VisualLayout.Row row) {
        int end = row.endExclusive;
        while (end > row.startOffset && text.charAt(end - 1) == ' ') end--;
        return end;
    }

    private void eraseRendered() {
        if (renderedRows <= 0) return;
        StringBuilder cleanup = new StringBuilder();
        cleanup.append(ansi.cursorUp(cursorRowFromTop));
        for (int index = 0; index < renderedRows; index++) {
            cleanup.append('\r').append(ansi.eraseLine());
            if (index < renderedRows - 1) cleanup.append('\n');
        }
        out.print(cleanup);
        out.flush();
        renderedRows = 0;
        cursorRowFromTop = 0;
    }

    private String promptStyled() {
        return ansi.dim() + "› " + ansi.reset();
    }

    /** Two-line styled welcome replacing the legacy banner in raw mode. */
    private void printWelcome() {
        String effort = session.reasoningEffort() == null ? "provider default" : session.reasoningEffort();
        String header = ansi.bold() + "java-agent " + Main.VERSION + ansi.reset()
                + " · " + session.model() + " · effort " + effort + " · " + config.workspace();
        if (session.id() != null) header += " · session " + session.id();
        out.println(header);
        out.println(ansi.dim()
                + "Ctrl+C cancel · Ctrl+C×2 exit · Ctrl+L clear · /help commands · Tab completes /"
                + ansi.reset());
    }

    private void refreshColumns(boolean force) {
        if (!sizeGate.due(force)) return;
        TerminalCapabilities.Size current = capabilities == null
                ? TerminalCapabilities.Size.FALLBACK : capabilities.size();
        columns = current.known() ? current.columns : TerminalCapabilities.Size.FALLBACK.columns;
    }
}
