package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consultation order with persistent rules bound to the router: exact deny,
 * exact allow, non-persistent always grants, then the prompt; yolo still
 * bypasses everything.
 */
class PermissionRulesRoutingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Tool tool = new FixedTool("write_file", "create smoke.txt");

    private String smokeKey() {
        return SessionRules.normalizeArguments(toolArguments());
    }

    private JsonNode toolArguments() {
        return new ObjectMapper().createObjectNode();
    }

    @Test
    void exactDenyBlocksInstantlyEvenOverAlwaysGrantsAndNeverPrompts() throws Exception {
        SessionApprovals grants = new SessionApprovals();
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> {
            throw new AssertionError("fallback must not run when a deny matches");
        }, grants);
        RecordingChannel channel = new RecordingChannel("always");
        router.attach(channel);

        // First request is answered "always" through the normal flow.
        AtomicBoolean firstApproved = new AtomicBoolean();
        Thread worker = new Thread(() -> firstApproved.set(router.approve(tool, toolArguments())));
        worker.start();
        ApprovalRouter.Request first = awaitRequest(router);
        first.complete(channel.serveApproval(first.tool, first.preview));
        worker.join(5000);
        assertTrue(firstApproved.get());
        assertEquals(1, channel.approvals.get());

        // A remembered exact deny now blocks the same identity without prompting.
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.DENY, "write_file", smokeKey());
        router.bindRules(() -> rules, false);
        assertFalse(router.approve(tool, toolArguments()), "deny beats the session grant");
        assertEquals(1, channel.approvals.get(), "no further prompt reaches the shell");
    }

    @Test
    void exactAllowShortCircuitsPrompting() throws Exception {
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> {
            throw new AssertionError("fallback must not run when an allow matches");
        });
        RecordingChannel channel = new RecordingChannel("no");
        router.attach(channel);
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.ALLOW, "write_file", smokeKey());
        router.bindRules(() -> rules, false);
        assertTrue(router.approve(tool, toolArguments()));
        assertTrue(router.approve(tool, toolArguments()), "repeats stay allowed");
        assertEquals(0, channel.approvals.get(), "an exact allow never prompts");
        assertEquals(SessionRules.Decision.UNRESOLVED,
                rules.decide("edit_file", smokeKey()));
    }

    @Test
    void unresolvedRequestsStillFlowThroughGrantsThenPrompts() throws Exception {
        SessionApprovals grants = new SessionApprovals();
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> false, grants);
        router.attach(new RecordingChannel("no"));
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.ALLOW, "other_tool", "{}");
        router.bindRules(() -> rules, false);

        grants.grant("write_file", smokeKey());
        assertTrue(router.approve(tool, toolArguments()),
            "the non-persistent always grant still satisfies unmatched requests");
    }

    @Test
    void allowAllPoliciesBypassDenyRulesExactlyAsBefore() {
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> true);
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.DENY, "write_file", smokeKey());
        router.bindRules(() -> rules, true);
        assertTrue(router.approve(tool, toolArguments()), "yolo keeps bypassing every permission check");
    }

    @Test
    void unboundRoutersBehaveByteIdenticallyToBefore() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> {
            fallbackCalls.incrementAndGet();
            return true;
        });
        assertTrue(router.approve(tool, toolArguments()));
        assertEquals(1, fallbackCalls.get(), "no bound rules means straight to grants and fallback");
    }

    @Test
    void rebindingFollowsTheActiveSavedSession() {
        AtomicReference<SessionRules> active = new AtomicReference<>();
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> false);
        router.bindRules(active::get, false);

        SessionRules savedSession = new SessionRules();
        savedSession.remember(SessionRules.Kind.DENY, "write_file", smokeKey());
        active.set(savedSession);
        assertFalse(router.approve(tool, toolArguments()));

        SessionRules fresh = new SessionRules();
        active.set(fresh);
        RecordingChannel channel = new RecordingChannel("no");
        router.attach(channel);
        AtomicBoolean prompted = new AtomicBoolean(true);
        Thread worker = new Thread(() -> prompted.set(router.approve(tool, toolArguments())));
        worker.start();
        try {
            ApprovalRouter.Request request = awaitRequest(router);
            request.complete(channel.serveApproval(request.tool, request.preview));
            worker.join(5000);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertFalse(prompted.get(), "a new session without rules prompts as usual");
    }

    @Test
    void askChildPreservesDeniesButDoesNotInheritAllowsOrAlwaysGrants() throws Exception {
        SessionApprovals grants = new SessionApprovals();
        ApprovalRouter parent = new ApprovalRouter((asked, arguments) -> false, grants);
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.ALLOW, "write_file", smokeKey());
        rules.remember(SessionRules.Kind.DENY, "edit_file", smokeKey());
        grants.grant("write_file", smokeKey());
        ApprovalPolicy child = parent.childAuthority(rules);

        Tool denied = new FixedTool("edit_file", "create smoke.txt");
        assertTrue(child.preflightDeny(denied, toolArguments()), "ASK child keeps the parent-session deny");

        RecordingChannel channel = new RecordingChannel("no");
        parent.attach(channel);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread worker = new Thread(() -> result.set(child.approve(tool, toolArguments())));
        worker.start();
        ApprovalRouter.Request request = awaitRequest(parent);
        request.complete(channel.serveApproval(request.tool, request.preview));
        worker.join(5000);
        assertFalse(result.get(), "the parent allow and always grant are not delegated to the child");
        assertEquals(1, channel.approvals.get(), "the unresolved ASK child request reaches the prompt");
    }

    @Test
    void autoChildPreservesDeniesAndFiltersParentAllows() {
        ApprovalRouter parent = new ApprovalRouter((asked, arguments) -> false);
        SessionRules rules = new SessionRules();
        rules.remember(SessionRules.Kind.DENY, "blocked_auto", smokeKey());
        rules.remember(SessionRules.Kind.ALLOW, "allowed_auto", smokeKey());
        ApprovalPolicy authority = parent.childAuthority(rules);
        ApprovalPolicy auto = SubagentAgentRunner.approval(PermissionMode.AUTO, authority,
                new PrintStream(new ByteArrayOutputStream()));

        Tool denied = new AutoTool("blocked_auto");
        Tool undelegatedAllow = new AutoTool("allowed_auto");
        assertTrue(auto.preflightDeny(denied, toolArguments()));
        assertFalse(auto.approve(denied, toolArguments()), "AUTO cannot bypass a projected deny");
        assertFalse(auto.preflightDeny(undelegatedAllow, toolArguments()),
                "the parent's allow is absent from child authority");
        assertTrue(auto.approve(undelegatedAllow, toolArguments()),
                "AUTO applies its own baseline after the allow is filtered");
    }

    @Test
    void childAuthorityRemainsBoundToItsOwningSessionAcrossRootSwitches() {
        ApprovalRouter parent = new ApprovalRouter((asked, arguments) -> false);
        SessionRules firstSession = new SessionRules();
        firstSession.remember(SessionRules.Kind.DENY, "write_file", smokeKey());
        ApprovalPolicy child = parent.childAuthority(firstSession);

        SessionRules secondSession = new SessionRules();
        secondSession.remember(SessionRules.Kind.DENY, "edit_file", smokeKey());
        parent.bindRules(() -> secondSession, false);

        assertTrue(child.preflightDeny(tool, toolArguments()),
                "switching the active root does not detach the owning session's deny");
        assertFalse(child.preflightDeny(new FixedTool("edit_file", "create smoke.txt"), toolArguments()),
                "the child does not acquire denies from a later active root session");
    }

    @Test
    void yoloChildBypassesProjectedDenies() {
        ApprovalPolicy denyAll = new ApprovalPolicy() {
            @Override public boolean approve(Tool tool, JsonNode arguments) { return false; }
            @Override public boolean preflightDeny(Tool tool, JsonNode arguments) { return true; }
        };
        ApprovalPolicy yolo = SubagentAgentRunner.approval(PermissionMode.YOLO, denyAll,
                new PrintStream(new ByteArrayOutputStream()));
        assertFalse(yolo.preflightDeny(tool, toolArguments()));
        assertTrue(yolo.approve(tool, toolArguments()));
    }

    @Test
    void yoloChildLosesInheritedBypassWhenParentModeIsLowered() {
        AtomicReference<PermissionMode> parentMode = new AtomicReference<>(PermissionMode.YOLO);
        ApprovalPolicy denyAll = new ApprovalPolicy() {
            @Override public boolean approve(Tool tool, JsonNode arguments) { return false; }
            @Override public boolean preflightDeny(Tool tool, JsonNode arguments) { return true; }
        };
        ApprovalPolicy child = SubagentAgentRunner.approval(PermissionMode.YOLO, denyAll,
                new PrintStream(new ByteArrayOutputStream()), parentMode::get);

        assertTrue(child.approve(tool, toolArguments()));
        assertFalse(child.preflightDeny(tool, toolArguments()));

        parentMode.set(PermissionMode.ASK);
        assertTrue(child.preflightDeny(tool, toolArguments()));
        assertFalse(child.approve(tool, toolArguments()),
                "a child cannot retain yolo approval after its parent returns to ask");
    }

    @Test
    void dynamicChildAuthorityTracksOwningRulesButNeverInheritsAllows() {
        ApprovalRouter parent = new ApprovalRouter((asked, arguments) -> false);
        AtomicReference<SessionRules> owningRules = new AtomicReference<>(new SessionRules());
        ApprovalPolicy child = parent.childAuthority(owningRules::get);
        owningRules.get().remember(SessionRules.Kind.DENY, "write_file", smokeKey());
        owningRules.get().remember(SessionRules.Kind.ALLOW, "allowed", smokeKey());

        assertTrue(child.preflightDeny(tool, toolArguments()));
        assertFalse(child.preflightDeny(new FixedTool("allowed", "create smoke.txt"), toolArguments()));
    }

    private static ApprovalRouter.Request awaitRequest(ApprovalRouter router) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ApprovalRouter.Request request = router.poll();
            if (request != null) return request;
            Thread.sleep(5);
        }
        throw new AssertionError("no request reached the shell within the deadline");
    }

    /** Channel stub that answers immediately, counting approval renders. */
    private static final class RecordingChannel implements ApprovalRouter.Channel {
        private final String approvalReply;
        private final AtomicInteger approvals = new AtomicInteger();
        private final AtomicReference<String> lastPreview = new AtomicReference<>();

        RecordingChannel(String approvalReply) {
            this.approvalReply = approvalReply;
        }

        @Override
        public String serveApproval(String toolName, String preview) {
            approvals.incrementAndGet();
            lastPreview.set(preview);
            return approvalReply;
        }

        @Override
        public String serveLineInput() {
            throw new UnsupportedOperationException();
        }
    }

    private static class FixedTool implements Tool {
        private final String name;
        private final String preview;

        FixedTool(String name, String preview) {
            this.name = name;
            this.preview = preview;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "test"; }
        @Override public ObjectNode parameters() { throw new UnsupportedOperationException(); }
        @Override public boolean requiresApproval() { return true; }
        @Override public String preview(JsonNode arguments) { return " create smoke.txt "; }
        @Override public String execute(JsonNode arguments) { return "ok"; }
    }

    private static final class AutoTool extends FixedTool {
        AutoTool(String name) { super(name, "create smoke.txt"); }
        @Override public boolean autoApprove(JsonNode arguments) { return true; }
    }
}
