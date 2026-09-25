package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Shared /compact command core for the raw shell and the legacy fallback
 * loop. Runs exactly one extra non-streaming model round-trip through
 * {@link Agent#summarize} — no tool definitions, nothing appended to durable
 * conversation state — then rebuilds agent input history as a leading
 * compacted_summary item plus the most recent exchanges verbatim and persists
 * immediately. Failures and Ctrl+C leave the conversation untouched.
 */
final class CompactCommands {
    static final String USAGE = "Usage: /compact";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CompactCommands() {
    }

    enum Outcome {
        /** Compacted and persisted with the active saved session. */
        COMPACTED,
        /** Compacted in memory only; session persistence is disabled. */
        COMPACTED_WITHOUT_PERSISTENCE
    }

    static final class Result {
        final Outcome outcome;
        final int itemsBefore;
        final int itemsAfter;
        final int compactionCount;

        Result(Outcome outcome, int itemsBefore, int itemsAfter, int compactionCount) {
            this.outcome = outcome;
            this.itemsBefore = itemsBefore;
            this.itemsAfter = itemsAfter;
            this.compactionCount = compactionCount;
        }
    }

    /** Friendly refusal line for conversations below the compaction threshold. */
    static String refusalLine(ArrayNode input) {
        return "Nothing to compact yet (" + input.size() + " items; no older context can be removed safely).";
    }

    /**
     * One-line confirmation including the rough size reduction, e.g.
     * "Compacted: 24 → 6 items · compaction #1 · summary replaces older history".
     */
    static String confirmation(Result result) {
        String persistence = result.outcome == Outcome.COMPACTED_WITHOUT_PERSISTENCE
                ? " · not persisted (--no-save)" : "";
        return "Compacted: " + result.itemsBefore + " → " + result.itemsAfter + " items · compaction #"
                + result.compactionCount + persistence;
    }

    /**
     * Validates arguments and eligibility, then runs the summarization
     * round-trip and persists the rebuilt history before swapping it into the
     * live session. Callers run this on their generation worker so Ctrl+C
     * interrupts the model call like any turn; an {@link InterruptedException}
     * means cancelled-with-no-changes. Failures name what actually happened:
     * a persist failure leaves the live conversation untouched, while a
     * failure after the persist says the summary was already saved.
     *
     * @throws IllegalArgumentException when arguments are present
     * @throws IllegalStateException    when the conversation is too small, or
     *                                  when the persisted history could not be applied live
     */
    static Result run(SessionRuntime session, String argument) throws IOException, InterruptedException {
        if (argument != null && !argument.strip().isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        ArrayNode input = session.conversation();
        ContextBudget budget = session.contextBudget();
        long fixedRequestTokens = session.fixedRequestTokens();
        if (!ConversationCompactor.eligible(input, budget, fixedRequestTokens)) {
            throw new IllegalStateException(refusalLine(input));
        }
        // Prove that at least the pinned request and a summary item can fit
        // before paying for the summarization round-trip.
        ConversationCompactor.rebuildForBudget(JSON, input, "x", budget, fixedRequestTokens);
        long summarizeOverhead = budget.estimateTextTokens(ConversationCompactor.SUMMARIZER_INSTRUCTIONS) + 16;
        String transcript = ConversationCompactor.renderTranscript(input, budget, summarizeOverhead);
        Agent.SummarizeResult summarized = session.summarizeWithUsage(transcript);
        // Persist this separate round-trip's usage even if the model returns an
        // empty summary or the resulting history cannot fit the configured budget.
        try {
            session.recordUsageOnly(summarized.inputTokens, summarized.outputTokens);
        } catch (IOException persistenceFailure) {
            throw new IOException("conversation left untouched: "
                    + safeMessage(persistenceFailure), persistenceFailure);
        }
        String summaryText = summarized.summary.strip();
        if (summaryText.isEmpty()) {
            throw new IOException("The model returned an empty summary; conversation left untouched");
        }
        ArrayNode rebuilt = ConversationCompactor.rebuildForBudget(JSON, input, summaryText,
                budget, fixedRequestTokens);
        try {
            session.applyCompaction(rebuilt, 0, 0);
        } catch (IOException persistenceFailure) {
            throw new IOException("conversation left untouched: "
                    + safeMessage(persistenceFailure), persistenceFailure);
        } catch (RuntimeException swapFailure) {
            throw new IllegalStateException("the compacted history was persisted, but applying it"
                    + " to the live session failed: " + safeMessage(swapFailure));
        }
        ObjectNode summary = (ObjectNode) rebuilt.get(0);
        return new Result(session.persistent() ? Outcome.COMPACTED : Outcome.COMPACTED_WITHOUT_PERSISTENCE,
                input.size(), rebuilt.size(), summary.path("compaction_count").asInt(0));
    }

    /** Synchronous entry point for the legacy loop; renders every outcome itself. */
    static void handle(SessionRuntime session, String argument, Ansi ansi, PrintStream out,
                       PrintStream error) {
        try {
            Result result = run(session, argument);
            out.println(ansi.dim() + confirmation(result) + ansi.reset());
        } catch (IllegalArgumentException | IllegalStateException shaped) {
            out.println(shaped.getMessage());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            out.println("Compaction cancelled; conversation untouched.");
        } catch (IOException failed) {
            error.println("java-agent: compaction failed: " + safeMessage(failed));
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
