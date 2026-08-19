package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentInterruptedRecoveryTest {
    @Test
    void restoreClosesAnInterruptedFunctionCallBeforeContinuing() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ArrayNode interrupted = json.createArrayNode();
        interrupted.addObject().put("role", "user").put("content", "run it");
        interrupted.addObject().put("type", "function_call").put("call_id", "call-pending")
                .put("name", "run_command").put("arguments", "{\"command\":\"work\"}");

        CapturingClient client = new CapturingClient(json);
        Agent agent = new Agent(json, client, List.of(), (tool, arguments) -> false,
                new PrintStream(OutputStream.nullOutputStream()), 2, "unused");
        agent.restoreConversation(interrupted, "saved instructions");

        assertEquals("recovered", agent.prompt("continue"));
        assertEquals(4, client.input.size());
        assertEquals("function_call_output", client.input.path(2).path("type").asText());
        assertEquals("call-pending", client.input.path(2).path("call_id").asText());
        assertEquals("continue", client.input.path(3).path("content").asText());
    }

    private static final class CapturingClient implements ResponsesClient {
        private final ObjectMapper json;
        private ArrayNode input;

        CapturingClient(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public ObjectNode complete(ArrayNode input, ArrayNode tools, String instructions) {
            this.input = input.deepCopy();
            ObjectNode response = json.createObjectNode().put("status", "completed");
            ObjectNode message = response.putArray("output").addObject().put("type", "message");
            message.putArray("content").addObject().put("type", "output_text").put("text", "recovered");
            return response;
        }
    }
}
