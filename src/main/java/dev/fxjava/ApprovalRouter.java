package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Supplier;

/**
 * Approval policy for interactive runs: consults persistent exact-match
 * session permission rules (denies before allows), then session "always"
 * grants, then hands the request to the raw shell's main loop through a
 * synchronous handoff so only the shell ever reads stdin while generating.
 * With no attached channel (one-shot or noninteractive requests) it delegates to the
 * active mode's wrapped policy. The active YOLO mode bypasses rules and grants;
 * ASK and AUTO resume their normal checks when selected again. Also routes
 * ask_user_question line input through the same channel.
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
    private volatile Supplier<SessionRules> rulesSupplier;
    private volatile boolean bypassRules;
    private volatile PermissionMode permissionMode;
    private final EnumMap<PermissionMode, ApprovalPolicy> modeFallbacks = new EnumMap<>(PermissionMode.class);

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

    /**
     * Binds the active session's persistent rules. The boolean retains the
     * legacy static allow-all behavior for routers without a selected mode.
     */
    void bindRules(Supplier<SessionRules> supplier, boolean allowAllPolicy) {
        rulesSupplier = supplier;
        bypassRules = allowAllPolicy;
    }

    void setModeFallback(PermissionMode mode, ApprovalPolicy policy) {
        modeFallbacks.put(mode, policy);
    }

    void setPermissionMode(PermissionMode mode) {
        permissionMode = mode;
    }

    PermissionMode permissionMode() {
        return permissionMode;
    }

    int grantCount() {
        return grants.count();
    }

    void clearSessionGrants() {
        grants.clear();
    }

    Request poll() {
        return pending.poll();
    }

    @Override
    public boolean preflightDeny(Tool tool, JsonNode arguments) {
        if (permissionMode == PermissionMode.YOLO) return false;
        if (permissionMode == null && bypassRules) return false;
        Supplier<SessionRules> supplier = permissionMode == null && bypassRules ? null : rulesSupplier;
        if (supplier != null) {
            SessionRules active = supplier.get();
            if (active != null) {
                SessionRules.Decision decision =
                        active.decide(tool.name(), SessionRules.normalizeArguments(arguments));
                return decision == SessionRules.Decision.DENY;
            }
        }
        return false;
    }

    @Override
    public boolean approve(Tool tool, JsonNode arguments) {
        PermissionMode mode = permissionMode;
        if (mode == PermissionMode.YOLO) return true;
        if (mode == null && bypassRules) return fallback.approve(tool, arguments);
        String preview = ToolPreview.safeText(ApprovalPrompt.flatten(tool.preview(arguments)));
        String canonicalArguments = SessionRules.normalizeArguments(arguments);
        Supplier<SessionRules> supplier = rulesSupplier;
        if (supplier != null) {
            SessionRules active = supplier.get();
            if (active != null) {
                SessionRules.Decision decision =
                        active.decide(tool.name(), SessionRules.normalizeArguments(arguments));
                if (decision == SessionRules.Decision.DENY) return false;
                if (decision == SessionRules.Decision.ALLOW) return true;
            }
        }
        if (grants.allows(tool.name(), canonicalArguments)) return true;
        Channel active = channel;
        if (active == null) {
            ApprovalPolicy modeFallback = mode == null ? null : modeFallbacks.get(mode);
            return (modeFallback == null ? fallback : modeFallback).approve(tool, arguments);
        }
        if (mode == PermissionMode.AUTO) return autoApprove(tool, arguments);
        return prompt(active, tool, arguments, preview, canonicalArguments);
    }

    /** Captures the parent's current deny authority without inheriting allows or grants. */
    ApprovalPolicy childAuthority(SessionRules parentRules) {
        SessionRules projected = parentRules == null ? new SessionRules() : parentRules.denyOnlyCopy();
        return childAuthority(() -> projected);
    }

    /** Dynamically follows denies in the child's owning root session. */
    ApprovalPolicy childAuthority(Supplier<SessionRules> parentRules) {
        return new ApprovalPolicy() {
            @Override
            public boolean preflightDeny(Tool tool, JsonNode arguments) {
                try {
                    SessionRules active = parentRules.get();
                    return active != null && active.decide(tool.name(), SessionRules.normalizeArguments(arguments))
                            == SessionRules.Decision.DENY;
                } catch (RuntimeException unavailable) {
                    return true;
                }
            }

            @Override
            public boolean approve(Tool tool, JsonNode arguments) {
                if (preflightDeny(tool, arguments)) return false;
                String preview = ToolPreview.safeText(ApprovalPrompt.flatten(tool.preview(arguments)));
                Channel active = channel;
                if (active == null) return fallback.approve(tool, arguments);
                return ApprovalRouter.this.prompt(active, tool, arguments, preview,
                        SessionRules.normalizeArguments(arguments));
            }
        };
    }

    private boolean prompt(Channel active, Tool tool, JsonNode arguments, String preview, String canonicalArguments) {
        try {
            Request request = new Request(tool.name(), preview, false);
            pending.put(request);
            return settle(request.await(), tool.name(), canonicalArguments);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean autoApprove(Tool tool, JsonNode arguments) {
        try {
            return tool.autoApprove(arguments);
        } catch (Exception invalid) {
            return false;
        }
    }

    private boolean settle(String reply, String tool, String canonicalArguments) {
        if (CANCELLED.equals(reply)) return false;
        if ("always".equals(reply)) grants.grant(tool, canonicalArguments);
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
