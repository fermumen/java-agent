package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Raw UI behavior through the same queued-input seam used on Windows. */
@Timeout(10)
class RawUiTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void resizeRepaintsWrappedDraftWithoutAKeypressAndDoesNotRepaintWhenUnchanged(int color) throws Exception {
        QueuedInputStream keys = new QueuedInputStream();
        AtomicReference<TerminalCapabilities.Size> size = new AtomicReference<>(new TerminalCapabilities.Size(24, 80));
        AtomicLong now = new AtomicLong();
        ObservedOutput output = new ObservedOutput();
        CountDownLatch draftPainted = output.expect("draft with enough text to wrap when the terminal narrows");
        keys.offer("draft with enough text to wrap when the terminal narrows".getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(keys, output, now::get, size::get, color != 0)) {
            CompletableFuture<Integer> run = fixture.start();
            try {
                assertTrue(draftPainted.await(2, TimeUnit.SECONDS));
                int frameStart = output.size();
                CountDownLatch smaller = output.expect(InputBox.top(40, Ansi.of(color != 0)));
                size.set(new TerminalCapabilities.Size(15, 40));
                now.set(250_000_000L);
                assertTrue(smaller.await(2, TimeUnit.SECONDS), "resize must repaint without stdin becoming readable");
                byte[] resizedBytes = output.toByteArray();
                String resizedFrame = new String(resizedBytes, frameStart, resizedBytes.length - frameStart,
                        StandardCharsets.UTF_8);
                assertTrue(resizedFrame.startsWith("\u001b[4A"),
                        "reflow must count visible cells, not color escapes: " + resizedFrame);
                CountDownLatch larger = output.expect(InputBox.top(80, Ansi.of(color != 0)));
                size.set(new TerminalCapabilities.Size(24, 80));
                now.set(500_000_000L);
                assertTrue(larger.await(2, TimeUnit.SECONDS));
                int before = output.size();
                now.set(750_000_000L);
                Thread.sleep(100);
                assertEquals(before, output.size(), "unchanged dimensions must not cause repeated frames");
            } finally {
                keys.close();
                assertEquals(0, run.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void retiredSessionsCommandIsRejectedWithOrWithoutPersistence(int noSave) throws Exception {
        List<String> args = new ArrayList<>(List.of("--workspace", temporary.toString(),
                "--session-root", temporary.resolve("retired-command-state").toString()));
        if (noSave != 0) args.add("--no-save");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = TestRawShell.run(args.toArray(String[]::new), Map.of("OPENAI_API_KEY", "test-key"),
                new ByteArrayInputStream("/sessions\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit);
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("Unknown command /sessions, try /help."), output);
        assertFalse(output.contains("Session persistence is disabled"), output);
    }

    @Test
    void bareResumeShowsPreviewsThenEnterResumesTheHighlightedSession() throws Exception {
        QueuedInputStream keys = new QueuedInputStream();
        ObservedOutput output = new ObservedOutput();
        CountDownLatch picker = output.expect("older conversation about windows");
        keys.offer("/resume\r".getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(keys, output, System::nanoTime,
                () -> new TerminalCapabilities.Size(24, 80))) {
            String currentId = fixture.session.id();
            CompletableFuture<Integer> run = fixture.start();
            try {
                assertTrue(picker.await(2, TimeUnit.SECONDS));
                assertEquals(currentId, fixture.session.id(), "bare /resume must not silently resume last");
                CountDownLatch resumed = output.expect("Resumed session: " + fixture.older.id());
                keys.offer("\u001b[B\r".getBytes(StandardCharsets.UTF_8));
                assertTrue(resumed.await(2, TimeUnit.SECONDS));
                assertEquals(fixture.older.id(), fixture.session.id());
                keys.offer("/exit\r".getBytes(StandardCharsets.UTF_8));
                assertEquals(0, run.get(2, TimeUnit.SECONDS));
            } finally {
                keys.close();
                run.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void shrinkingTheViewportDoesNotScrollThePickerOutOfView() throws Exception {
        QueuedInputStream keys = new QueuedInputStream();
        ObservedOutput output = new ObservedOutput();
        AtomicReference<TerminalCapabilities.Size> size = new AtomicReference<>(new TerminalCapabilities.Size(24, 80));
        CountDownLatch picker = output.expect("older conversation about windows");
        keys.offer("/resume\r".getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(keys, output, System::nanoTime, size::get, true)) {
            CompletableFuture<Integer> run = fixture.start();
            try {
                assertTrue(picker.await(2, TimeUnit.SECONDS));
                int start = output.size();
                CountDownLatch smaller = output.expect(InputBox.top(40, Ansi.of(true)));
                size.set(new TerminalCapabilities.Size(8, 40));
                assertTrue(smaller.await(2, TimeUnit.SECONDS));
                byte[] bytes = output.toByteArray();
                String frame = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
                assertTrue(frame.chars().filter(c -> c == '\n').count() < 8,
                        "erasing the old tail must not scroll the new picker off-screen");
            } finally {
                keys.close();
                assertEquals(0, run.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void escapeDismissesResumePickerWithoutChangingSessions() throws Exception {
        QueuedInputStream keys = new QueuedInputStream();
        ObservedOutput output = new ObservedOutput();
        CountDownLatch picker = output.expect("older conversation about windows");
        keys.offer("/resume\r".getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(keys, output, System::nanoTime,
                () -> new TerminalCapabilities.Size(24, 80))) {
            String currentId = fixture.session.id();
            CompletableFuture<Integer> run = fixture.start();
            try {
                assertTrue(picker.await(2, TimeUnit.SECONDS));
                keys.offer("\u001b\u001b/exit\r".getBytes(StandardCharsets.UTF_8));
                assertEquals(0, run.get(2, TimeUnit.SECONDS));
                assertEquals(currentId, fixture.session.id());
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("Resumed session:"));
            } finally {
                keys.close();
                run.get(2, TimeUnit.SECONDS);
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final SessionRuntime session;
        final SessionStore.Snapshot older;
        final McpRuntime mcp;
        final InteractiveShell shell;
        final TerminalCapabilities capabilities;

        Fixture(QueuedInputStream keys, ObservedOutput output, Spinner.Clock clock,
                TerminalCapabilities.SizeSource size) throws Exception {
            this(keys, output, clock, size, false);
        }

        Fixture(QueuedInputStream keys, ObservedOutput output, Spinner.Clock clock,
                TerminalCapabilities.SizeSource size, boolean color) throws Exception {
            Path state = temporary.resolve("state");
            SessionStore oldStore = new SessionStore(json, state, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            SessionStore.Snapshot saved = oldStore.create(temporary, "model", "instructions");
            ArrayNode conversation = json.createArrayNode();
            conversation.addObject().put("role", "user").put("content", "older conversation about windows");
            older = oldStore.update(saved, conversation, "instructions");
            PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
            PrintStream error = new PrintStream(PrintStream.nullOutputStream());
            Agent agent = new Agent(json, (input, tools, instructions) -> {
                throw new AssertionError("UI-only commands must not call the model");
            }, List.of(), (tool, arguments) -> false, error, 1, "instructions");
            session = SessionRuntime.start(agent, new SessionStore(json, state), temporary,
                    "model", "instructions", null);
            mcp = McpRuntime.load(json, state.resolve("missing-mcp.json"));
            shell = new InteractiveShell(session, new AgentConfig("key", "http://127.0.0.1", "model",
                    temporary, 1, PermissionMode.ASK), "instructions", mcp, state, keys, out, error,
                    Ansi.of(color), new ApprovalRouter((tool, arguments) -> false), "test", clock);
            capabilities = TerminalCapabilities.detect(Map.of(), true, size);
        }

        CompletableFuture<Integer> start() {
            return CompletableFuture.supplyAsync(() -> {
                try { return shell.run(null, capabilities); }
                catch (Exception failed) { throw new RuntimeException(failed); }
            });
        }

        @Override public void close() throws Exception { mcp.close(); }
    }

    private static final class ObservedOutput extends ByteArrayOutputStream {
        private final List<Watch> watches = new ArrayList<>();

        synchronized CountDownLatch expect(String text) {
            Watch watch = new Watch(text, count);
            watches.add(watch);
            return watch.seen;
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            super.write(bytes, offset, length);
            for (Watch watch : watches) {
                if (new String(buf, watch.start, count - watch.start, StandardCharsets.UTF_8).contains(watch.text)) {
                    watch.seen.countDown();
                }
            }
        }

        private static final class Watch {
            final String text;
            final int start;
            final CountDownLatch seen = new CountDownLatch(1);
            Watch(String text, int start) { this.text = text; this.start = start; }
        }
    }
}
