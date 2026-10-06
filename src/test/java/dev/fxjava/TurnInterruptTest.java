package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Raw-shell cancellation and the next message through the real input loop. */
@Timeout(10)
class TurnInterruptTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(ints = {1, 64})
    void doubleEscapeInterruptsTheTurnAndPreservesTheNextDraft(int chunkSize) throws Exception {
        ScriptedClient client = new ScriptedClient();
        String output = run(client, "queued \u001b\u001breplacement", chunkSize);
        assertTrue(client.interrupted, "Esc Esc must interrupt the running model request");
        assertEquals(2, client.calls, "the shell must remain open for the next message");
        assertEquals("queued replacement", client.lastPrompt, "no type-ahead may be lost");
        assertTrue(output.contains("Esc Esc cancelled"), output);
        assertTrue(output.contains("replacement answer"), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approval", "answer"})
    void doubleEscapeAlsoInterruptsTurnsWaitingForInlineInput(String request) throws Exception {
        ScriptedClient client = new ScriptedClient(request);
        String output = run(client, "\u001b\u001b", 64);
        assertTrue(client.interrupted, "Esc Esc must cancel the turn, not just dismiss its prompt");
        assertEquals(2, client.calls);
        assertEquals("replacement", client.lastPrompt);
        assertTrue(output.contains(request.equals("approval") ? "Allow test?" : "Choose 1-1:"), output);
        assertTrue(output.contains("replacement answer"), output);
    }

    @Test
    void ctrlCStillInterruptsWithoutExitingTheShell() throws Exception {
        ScriptedClient client = new ScriptedClient();
        String output = run(client, "queued \u0003replacement", 64);
        assertTrue(client.interrupted);
        assertEquals(2, client.calls);
        assertEquals("queued replacement", client.lastPrompt);
        assertTrue(output.contains("^C cancelled"), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u001b", "\u001b[A", "\u001b[200~\u001b\u001b\u001b[201~"})
    void singleEscapeArrowKeysAndPastedEscapesDoNotInterrupt(String keys) throws Exception {
        ScriptedClient client = new ScriptedClient();
        String output = run(client, keys, 64);
        assertFalse(client.interrupted);
        assertEquals(1, client.calls);
        assertTrue(output.contains("original answer"), output);
        assertFalse(output.contains("cancelled"), output);
    }

    private String run(ScriptedClient client, String keys, int chunkSize) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream error = new PrintStream(errors, true, StandardCharsets.UTF_8);
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> true, error, 1,
                "instructions");
        SessionRuntime session = SessionRuntime.start(agent, null, temporary, "model",
                "instructions", null);
        AgentConfig config = new AgentConfig("key", "http://127.0.0.1", "model", temporary, 1,
                PermissionMode.ASK);
        ApprovalRouter approval = new ApprovalRouter((tool, arguments) -> false);
        client.approval = approval;
        try (McpRuntime mcp = McpRuntime.load(json, temporary.resolve("missing-mcp.json"))) {
            InteractiveShell shell = new InteractiveShell(session, config, "instructions", mcp,
                    temporary.resolve("state"), new ScriptedInput(client, keys, chunkSize),
                    out, error, Ansi.of(false), approval, "test", System::nanoTime);
            assertEquals(0, shell.run(null, TerminalCapabilities.detect(Map.of(), false)));
        }
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private final class ScriptedClient implements ResponsesClient {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile int calls;
        volatile boolean interrupted;
        String lastPrompt;
        ApprovalRouter approval;
        final String inlineRequest;

        ScriptedClient() {
            this(null);
        }

        ScriptedClient(String inlineRequest) {
            this.inlineRequest = inlineRequest;
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions)
                throws IOException, InterruptedException {
            lastPrompt = input.get(input.size() - 1).path("content").asText();
            if (++calls == 1) {
                started.countDown();
                try {
                    if ("approval".equals(inlineRequest)) {
                        approval.approve(new Tool() {
                            @Override public String name() { return "test"; }
                            @Override public String description() { return "Test approval"; }
                            @Override public ObjectNode parameters() { return json.createObjectNode(); }
                            @Override public boolean requiresApproval() { return true; }
                            @Override public String preview(com.fasterxml.jackson.databind.JsonNode arguments) {
                                return "test action";
                            }
                            @Override public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                                throw new AssertionError("cancelled tools must not execute");
                            }
                        }, json.createObjectNode());
                    } else if ("answer".equals(inlineRequest)) {
                        approval.questions().ask(new AskUserTool.Question("Choose an option", List.of(
                                new AskUserTool.Option("Option", ""))));
                    } else {
                        release.await();
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                    throw cancelled;
                }
            }
            ObjectNode response = json.createObjectNode().put("id", "response-" + calls);
            ObjectNode message = response.putArray("output").addObject();
            message.put("type", "message").put("role", "assistant");
            message.putArray("content").addObject().put("type", "output_text")
                    .put("text", calls == 1 ? "original answer" : "replacement answer");
            return response;
        }
    }

    /** The first request stays blocked until its keys have been dispatched. */
    private static final class ScriptedInput extends InputStream {
        private final ScriptedClient client;
        private final byte[][] chunks;
        private final int chunkSize;
        private int index;
        private int offset;

        ScriptedInput(ScriptedClient client, String keys, int chunkSize) {
            this.client = client;
            this.chunkSize = chunkSize;
            this.chunks = new byte[][] {
                    "first\r".getBytes(StandardCharsets.UTF_8),
                    keys.getBytes(StandardCharsets.UTF_8),
                    (client.inlineRequest == null ? "\r" : "replacement\r")
                            .getBytes(StandardCharsets.UTF_8),
                    "/exit\r".getBytes(StandardCharsets.UTF_8)
            };
        }

        @Override
        public int available() {
            if (index == 1 && client.inlineRequest == null && client.started.getCount() == 0) {
                return chunks[index].length - offset;
            }
            if (index >= 2) client.release.countDown();
            return 0;
        }

        @Override
        public int read(byte[] buffer, int start, int length) {
            if (index >= chunks.length) return -1;
            int count = Math.min(length, chunks[index].length - offset);
            if (index == 1) count = Math.min(count, chunkSize);
            System.arraycopy(chunks[index], offset, buffer, start, count);
            offset += count;
            if (offset == chunks[index].length) {
                index++;
                offset = 0;
            }
            return count;
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
        }
    }
}
