package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Handoff routing: session grants, channel decisions, legacy fallback. */
class ApprovalRouterTest {
    private final Tool tool = new FixedTool("write_file", "create smoke.txt");

    @Test
    void withoutChannelDelegatesToFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> {
            fallbackCalls.incrementAndGet();
            return true;
        });
        assertTrue(router.approve(tool, toolArguments()));
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void alwaysReplyGrantsAndSkipsLaterPrompts() throws Exception {
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> {
            throw new AssertionError("fallback must not run while a channel is attached");
        });
        RecordingChannel channel = new RecordingChannel("always");
        router.attach(channel);
        AtomicBoolean first = new AtomicBoolean();
        Thread worker = new Thread(() -> first.set(router.approve(tool, toolArguments())));
        worker.start();
        servePending(router, channel);
        worker.join(5000);

        assertTrue(first.get());
        assertEquals("create smoke.txt", channel.lastPreview.get());
        assertEquals(1, router.grantCount());
        assertEquals(1, channel.approvals.get());
        assertTrue(router.approve(tool, toolArguments()), "identical pair is pre-approved");
        assertEquals(1, channel.approvals.get(), "no second prompt reaches the shell");
    }

    @Test
    void alwaysGrantDoesNotReuseTheSamePreviewForDifferentStructuredActions() throws Exception {
        Tool command = new FixedTool("run_command", "run `echo Exact`");
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> false);
        RecordingChannel channel = new RecordingChannel("always");
        router.attach(channel);

        assertTrue(approveWithShell(router, command, arguments(
                "{\"command\":\"echo Exact\",\"working_directory\":\"src\"}"), channel));
        assertTrue(router.approve(command, arguments(
                "{ \"working_directory\": \"src\", \"command\": \"echo Exact\" }")),
                "canonical JSON key order should still match");
        assertEquals(1, channel.approvals.get());

        assertTrue(approveWithShell(router, command, arguments(
                "{\"command\":\"echo Exact && echo changed\",\"working_directory\":\"src\"}"), channel),
                "a changed command suffix should prompt even when preview text is unchanged");
        assertTrue(approveWithShell(router, command, arguments(
                "{\"command\":\"echo Exact\",\"working_directory\":\"test\"}"), channel),
                "a changed working directory should prompt even when preview text is unchanged");
        assertEquals(3, channel.approvals.get());
        assertEquals(3, router.grantCount());
    }

    @Test
    void interactivePreviewEscapesTerminalControlsBeforeDisplay() throws Exception {
        Tool unsafePreview = new FixedTool("dangerous", "run\u001b[2J\nnext");
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> false);
        RecordingChannel channel = new RecordingChannel("no");
        router.attach(channel);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread worker = new Thread(() -> result.set(router.approve(unsafePreview, toolArguments())));
        worker.start();
        ApprovalRouter.Request request = awaitRequest(router);
        assertTrue(request.preview.contains("\\u{1b}[2J next"), request.preview);
        assertFalse(request.preview.contains("\u001b"), "escape bytes cannot alter the terminal display");
        request.complete("no");
        worker.join(5000);
        assertFalse(result.get());
    }

    @Test
    void noAndCancelledRepliesDenyWithoutGranting() throws Exception {
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> false);
        RecordingChannel channel = new RecordingChannel("no");
        router.attach(channel);
        AtomicBoolean denied = new AtomicBoolean(true);
        Thread worker = new Thread(() -> denied.set(router.approve(tool, toolArguments())));
        worker.start();
        servePending(router, channel);
        worker.join(5000);
        assertFalse(denied.get());
        assertEquals(0, router.grantCount());

        RecordingChannel cancelling = new RecordingChannel(ApprovalRouter.CANCELLED);
        router.attach(cancelling);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        Thread cancelThread = new Thread(() -> cancelled.set(router.approve(tool, toolArguments())));
        cancelThread.start();
        servePending(router, cancelling);
        cancelThread.join(5000);
        assertFalse(cancelled.get());
        assertEquals(0, router.grantCount());
    }

    @Test
    void detachedRouterFallsBackAgain() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> {
            fallbackCalls.incrementAndGet();
            return true;
        });
        router.attach(new RecordingChannel("always"));
        router.detach();
        assertTrue(router.approve(tool, toolArguments()));
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    void lineSourceReturnsNullWhenDetached() throws Exception {
        ApprovalRouter router = new ApprovalRouter((asked, arguments) -> false);
        assertNull(router.lineSource().get());
        RecordingChannel channel = new RecordingChannel("typed answer");
        router.attach(channel);
        AtomicReference<String> line = new AtomicReference<>("sentinel");
        Thread worker = new Thread(() -> line.set(router.lineSource().get()));
        worker.start();
        ApprovalRouter.Request request = awaitRequest(router);
        assertTrue(request.lineInput);
        request.complete(channel.lineReply);
        worker.join(5000);
        assertEquals("typed answer", line.get());
        assertEquals(0, channel.approvals.get(), "line requests never touch the approval path");
    }

    /** Plays the shell's watcher: pick up the pending request and answer via the channel. */
    private static void servePending(ApprovalRouter router, RecordingChannel channel)
            throws InterruptedException, java.io.IOException {
        ApprovalRouter.Request request = awaitRequest(router);
        String reply = request.lineInput ? channel.serveLineInput()
                : channel.serveApproval(request.tool, request.preview);
        request.complete(reply);
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

    private JsonNode toolArguments() {
        return new ObjectMapper().createObjectNode();
    }

    private JsonNode arguments(String raw) throws Exception {
        return new ObjectMapper().readTree(raw);
    }

    private static boolean approveWithShell(ApprovalRouter router, Tool asked, JsonNode args,
                                            RecordingChannel channel) throws Exception {
        AtomicBoolean result = new AtomicBoolean();
        Thread worker = new Thread(() -> result.set(router.approve(asked, args)));
        worker.start();
        ApprovalRouter.Request request = awaitRequest(router);
        request.complete(channel.serveApproval(request.tool, request.preview));
        worker.join(5000);
        assertFalse(worker.isAlive(), "approval worker should finish after the shell reply");
        return result.get();
    }

    /** Channel stub that answers immediately, counting approval renders. */
    private static final class RecordingChannel implements ApprovalRouter.Channel {
        private final String approvalReply;
        private final String lineReply;
        private final AtomicInteger approvals = new AtomicInteger();
        private final AtomicReference<String> lastPreview = new AtomicReference<>();

        RecordingChannel(String reply) {
            this(reply, reply);
        }

        RecordingChannel(String approvalReply, String lineReply) {
            this.approvalReply = approvalReply;
            this.lineReply = lineReply;
        }

        @Override
        public String serveApproval(String toolName, String preview) {
            approvals.incrementAndGet();
            lastPreview.set(preview);
            return approvalReply;
        }

        @Override
        public String serveLineInput() {
            return lineReply;
        }
    }

    private static final class FixedTool implements Tool {
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
        @Override public String preview(JsonNode arguments) { return " " + preview + "\t\n"; }
        @Override public String execute(JsonNode arguments) { return "ok"; }
    }
}
