package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Supplier;

/**
 * Approval policy for interactive runs: consults session "always" grants,
 * then hands the request to the raw shell's main loop through a synchronous
 * handoff so only the shell ever reads stdin while generating. With no
 * attached channel (non-TTY or legacy fallback) it delegates byte-identically
 * to the wrapped policy. Also routes ask_user_question line input through the
 * same channel.
 */
final class ApprovalRouter implements ApprovalPolicy {
    /** Marker returned over the reply slot when the user cancelled or EOF hit. */
    static final String CANCELLED = "\u0000cancelled";

    /** Raw-shell side of the handoff; invoked on the shell's main loop only. */
    interface Channel {
        /** Renders the boxed prompt and reads one decision key; never returns null. */
        String serveApproval(String tool, String preview)
                throws IOException, InterruptedException;

        /** Reads one echoed input line; returns the cancel marker on EOF/cancel. */
        String serveLineInput() throws IOException, InterruptedException;
    }

    /** One pending exchange between a worker and the shell's input loop. */
    static final class Request {
        final String tool;
        final String preview;
        final boolean lineInput;
        final AskUserTool.Question question;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile String reply;

        Request(String tool, String preview, boolean lineInput) {
            this(tool, preview, lineInput, null);
        }

        Request(AskUserTool.Question question) {
            this("", "", false, question);
        }

        private Request(String tool, String preview, boolean lineInput, AskUserTool.Question question) {
            this.tool = tool;
            this.preview = preview;
            this.lineInput = lineInput;
            this.question = question;
        }

        String await() throws InterruptedException {
            done.await();
            return reply;
        }

        void complete(String value) {
            reply = value;
            done.countDown();
        }
    }

    private final ApprovalPolicy fallback;
    private final SessionApprovals grants;
    private final SynchronousQueue<Request> pending = new SynchronousQueue<>();
    private volatile Channel channel;

    ApprovalRouter(ApprovalPolicy fallback) {
        this(fallback, new SessionApprovals());
    }

    ApprovalRouter(ApprovalPolicy fallback, SessionApprovals grants) {
        this.fallback = fallback;
        this.grants = grants;
    }

    void attach(Channel shellChannel) {
        channel = shellChannel;
    }

    void detach() {
        channel = null;
    }

    Channel channel() {
        return channel;
    }

    int grantCount() {
        return grants.count();
    }

    Request poll() {
        return pending.poll();
    }

    @Override
    public boolean approve(Tool tool, JsonNode arguments) {
        String preview = ApprovalPrompt.flatten(tool.preview(arguments));
        if (grants.allows(tool.name(), preview)) return true;
        Channel active = channel;
        if (active == null) return fallback.approve(tool, arguments);
        try {
            Request request = new Request(tool.name(), preview, false);
            pending.put(request);
            return settle(request.await(), tool.name(), preview);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean settle(String reply, String tool, String preview) {
        if (CANCELLED.equals(reply)) return false;
        if ("always".equals(reply)) grants.grant(tool, preview);
        return !"no".equals(reply);
    }

    /** Line source for AskUserTool: routed through the shell while attached. */
    Supplier<String> lineSource() {
        return () -> {
            Channel active = channel;
            if (active == null) return null;
            try {
                Request request = new Request("", "", true);
                pending.put(request);
                String line = request.await();
                return CANCELLED.equals(line) ? null : line;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
    }

    /** Inline question flow for AskUserTool: routed through the shell while attached. */
    QuestionFlow questions() {
        return question -> {
            Channel active = channel;
            if (active == null) return null;
            try {
                Request request = new Request(question);
                pending.put(request);
                String reply = request.await();
                return CANCELLED.equals(reply) ? null : reply;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
    }
}
