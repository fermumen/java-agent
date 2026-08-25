package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turn listener events fire around tool execution with accurate error flags. */
class AgentTurnListenerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void firesStartAndEndAroundASuccessfulToolCall() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-1", "echo", "{\"value\":\"hello\"}"),
                textResponse("Done"));
        RecordingListener listener = new RecordingListener();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Done", agent.prompt("Do it", ignored -> { }, listener));
        assertEquals(List.of("start:echo hello", "end:echo:false"), listener.events);
    }

    @Test
    void deniedApprovalFlagsTheToolCallAsError() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-2", "echo", "{\"value\":\"no\"}"),
                textResponse("Denied"));
        RecordingListener listener = new RecordingListener();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Denied", agent.prompt("Do it", ignored -> { }, listener));
        assertEquals(List.of("start:echo no", "end:echo:true"), listener.events);
    }

    @Test
    void unknownToolsSkipStartButStillEndAsErrors() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-3", "ghost", "{}"),
                textResponse("Recovered"));
        RecordingListener listener = new RecordingListener();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");

        assertEquals("Recovered", agent.prompt("Do it", ignored -> { }, listener));
        assertTrue(listener.events.stream().noneMatch(event -> event.startsWith("start:")));
        assertEquals(List.of("end:ghost:true"), listener.events);
    }

    @Test
    void promptsWithoutAListenerKeepWorking() throws Exception {
        FakeClient client = new FakeClient(toolResponse("call-4", "echo", "{\"value\":\"hi\"}"),
                textResponse("Ok"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        Agent agent = new Agent(json, client, List.of(new EchoTool()), (tool, arguments) -> true,
                new PrintStream(progress, true, StandardCharsets.UTF_8), 5, "system");

        assertEquals("Ok", agent.prompt("Do it"));
        assertTrue(progress.toString(StandardCharsets.UTF_8).contains("[tool] echo hi"));
    }

    private ObjectNode toolResponse(String callId, String name, String arguments) {
        ObjectNode response = completedResponse();
        ArrayNode output = response.putArray("output");
        output.addObject().put("id", "rs_1").put("type", "reasoning")
                .put("encrypted_content", "encrypted-state").putArray("summary");
        output.addObject().put("id", "fc_1").put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments)
                .put("status", "completed");
        return response;
    }

    private ObjectNode textResponse(String text) {
        ObjectNode response = completedResponse();
        ObjectNode message = response.putArray("output").addObject();
        message.put("id", "msg_1").put("type", "message").put("role", "assistant")
                .put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text").put("text", text)
                .putArray("annotations");
        return response;
    }

    private ObjectNode completedResponse() {
        return json.createObjectNode().put("id", "resp_test").put("object", "response")
                .put("status", "completed");
    }

    private static final class RecordingListener implements Agent.TurnListener {
        final List<String> events = new ArrayList<>();

        @Override public void onToolStart(String name, String preview) {
            events.add("start:" + preview);
        }

        @Override public void onToolEnd(String name, boolean error) {
            events.add("end:" + name + ":" + error);
        }
    }

    private static final class FakeClient implements ResponsesClient {
        private final Queue<ObjectNode> responses = new ArrayDeque<>();

        FakeClient(ObjectNode... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            return responses.remove();
        }
    }

    private final class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo a value"; }
        @Override public ObjectNode parameters() { return json.createObjectNode().put("type", "object"); }
        @Override public boolean requiresApproval() { return true; }
        @Override public String preview(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo " + arguments.path("value").asText();
        }
        @Override public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "echo: " + arguments.path("value").asText();
        }
    }
}
