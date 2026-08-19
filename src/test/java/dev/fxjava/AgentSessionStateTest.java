package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionStateTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void restoredConversationContinuesWithSavedInputAndInstructions() throws Exception {
        RecordingClient firstClient = new RecordingClient("first answer");
        Agent first = agent(firstClient, "original instructions");
        assertEquals("first answer", first.prompt("first question"));

        ArrayNode saved = first.snapshotInput();
        saved.path(0).deepCopy();

        RecordingClient resumedClient = new RecordingClient("second answer");
        Agent resumed = agent(resumedClient, "unused instructions");
        resumed.restoreConversation(saved, first.instructions());
        saved.removeAll();

        assertEquals("second answer", resumed.prompt("second question"));
        ArrayNode request = resumedClient.requests.get(0);
        assertEquals(3, request.size());
        assertEquals("first question", request.path(0).path("content").asText());
        assertEquals("message", request.path(1).path("type").asText());
        assertEquals("second question", request.path(2).path("content").asText());
        assertEquals("original instructions", resumedClient.instructions.get(0));
    }

    @Test
    void clearDropsSavedHistoryAndUsesNewInstructions() throws Exception {
        RecordingClient client = new RecordingClient("before", "after");
        Agent agent = agent(client, "before instructions");
        agent.prompt("old question");
        agent.clearConversation("fresh instructions");

        agent.prompt("new question");
        ArrayNode request = client.requests.get(1);
        assertEquals(1, request.size());
        assertEquals("new question", request.path(0).path("content").asText());
        assertEquals("fresh instructions", client.instructions.get(1));
    }

    @Test
    void credentialedWebFetchArgumentsAreRedactedInSnapshots() throws Exception {
        AtomicInteger step = new AtomicInteger();
        ResponsesClient client = (input, tools, instructions) -> {
            ObjectNode response = json.createObjectNode().put("status", "completed");
            if (step.getAndIncrement() == 0) {
                response.putArray("output").addObject().put("type", "function_call")
                        .put("call_id", "fetch-secret").put("name", "web_fetch")
                        .put("arguments", "{\"url\":\"https://user:pass" + "@"
                                + "example.com/docs?safe=ok&token=query-secret\"}");
            } else {
                response.putArray("output").addObject().put("type", "message").putArray("content")
                        .addObject().put("type", "output_text").put("text", "handled");
            }
            return response;
        };
        Agent agent = agent(client, "instructions");
        assertEquals("handled", agent.prompt("fetch"));
        String snapshot = agent.snapshotInput().toString();
        assertFalse(snapshot.contains("user:pass"), snapshot);
        assertFalse(snapshot.contains("query-secret"), snapshot);
        assertTrue(snapshot.contains("[redacted]" + "@" + "example.com"));
    }

    @Test
    void sensitiveStructuredToolArgumentsAreRedactedInSnapshots() throws Exception {
        AtomicInteger step = new AtomicInteger();
        ResponsesClient client = (input, tools, instructions) -> {
            ObjectNode response = json.createObjectNode().put("status", "completed");
            if (step.getAndIncrement() == 0) {
                response.putArray("output").addObject().put("type", "function_call")
                        .put("call_id", "command-secret").put("name", "run_command")
                        .put("arguments", "{\"command\":\"echo ok\",\"api_key\":\"secret-value\","
                                + "\"nested\":{\"password\":\"hidden\"}}");
            } else {
                response.putArray("output").addObject().put("type", "message").putArray("content")
                        .addObject().put("type", "output_text").put("text", "handled");
            }
            return response;
        };
        Agent agent = agent(client, "instructions");
        assertEquals("handled", agent.prompt("run"));
        String snapshot = agent.snapshotInput().toString();
        assertTrue(snapshot.contains("echo ok"), snapshot);
        assertTrue(snapshot.contains("[redacted]"), snapshot);
        assertFalse(snapshot.contains("secret-value"), snapshot);
        assertFalse(snapshot.contains("hidden"), snapshot);
    }

    private Agent agent(ResponsesClient client, String instructions) {
        return new Agent(json, client, List.of(), (tool, arguments) -> false,
                new PrintStream(OutputStream.nullOutputStream()), 2, instructions);
    }

    private final class RecordingClient implements ResponsesClient {
        private final List<String> answers;
        private final ArrayList<ArrayNode> requests = new ArrayList<>();
        private final ArrayList<String> instructions = new ArrayList<>();
        private int index;

        RecordingClient(String... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            requests.add(input.deepCopy());
            this.instructions.add(instructions);
            ObjectNode response = json.createObjectNode().put("status", "completed");
            ObjectNode message = response.putArray("output").addObject();
            message.put("type", "message").put("role", "assistant");
            message.putArray("content").addObject().put("type", "output_text")
                    .put("text", answers.get(index++));
            return response;
        }
    }
}
