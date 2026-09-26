package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelSelectionIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void effortIsOmittedByDefaultAndAppliedToLaterStreamingAndSummaryRequests() throws Exception {
        try (ResponsesServer server = new ResponsesServer()) {
            AgentConfig config = new AgentConfig("test-key", server.baseUrl(), "model-one",
                    workspace, 2, PermissionMode.ASK);
            OpenAiResponsesClient client = client(config);

            client.complete(json.createArrayNode(), json.createArrayNode(), "instructions");
            JsonNode first = server.request(0);
            assertEquals("model-one", first.path("model").asText());
            assertFalse(first.has("reasoning"), first.toString());

            SessionRuntime session = SessionRuntime.start(agent(client), null, workspace,
                    config.model(), "instructions", null);
            session.setModel("model-two");
            session.setReasoningEffort("HIGH");
            assertEquals("model-two", session.model());
            assertEquals("high", session.reasoningEffort());

            client.complete(json.createArrayNode(), json.createArrayNode(), "instructions", ignored -> { });
            JsonNode streamed = server.request(1);
            assertEquals("model-two", streamed.path("model").asText());
            assertEquals("high", streamed.path("reasoning").path("effort").asText());

            assertEquals("summary", session.summarize("summarize this"));
            JsonNode summarized = server.request(2);
            assertEquals("model-two", summarized.path("model").asText());
            assertEquals("high", summarized.path("reasoning").path("effort").asText());

            session.setReasoningEffort(null);
            session.summarize("default effort");
            JsonNode cleared = server.request(3);
            assertEquals("model-two", cleared.path("model").asText());
            assertFalse(cleared.has("reasoning"), cleared.toString());
        }
    }

    @Test
    void subagentsInheritActiveParentSelectionAndHonorPerChildOverrides() throws Exception {
        try (ResponsesServer server = new ResponsesServer()) {
            AtomicReference<ModelSelection> parent = new AtomicReference<>(new ModelSelection("parent-one", "medium"));
            AtomicReference<List<Tool>> tools = new AtomicReference<>(List.of());
            SubagentManager.ChildConfiguration inherited = new SubagentManager.ChildConfiguration(
                    "child-1", "Worker", null, null, PermissionMode.ASK);
            SubagentAgentRunner runner = new SubagentAgentRunner(json, "test-key", server.baseUrl(),
                    "unused-fallback", workspace, 2, workspace.resolve("sessions"), tools,
                    (tool, arguments) -> true, new PrintStream(OutputStream.nullOutputStream()),
                    inherited, null, new ContextBudget(), parent::get);

            runner.prompt("first task");
            assertSelection(server.request(0), "parent-one", "medium");

            parent.set(new ModelSelection("parent-two", "max"));
            runner.prompt("second task");
            assertSelection(server.request(1), "parent-two", "max");

            SubagentManager.ChildConfiguration overrideEffort = new SubagentManager.ChildConfiguration(
                    "child-1", "Worker", null, "low", PermissionMode.ASK);
            runner.configure(overrideEffort);
            runner.prompt("third task");
            assertSelection(server.request(2), "parent-two", "low");

            SubagentManager.ChildConfiguration overrideModel = new SubagentManager.ChildConfiguration(
                    "child-1", "Worker", "child-model", null, PermissionMode.ASK);
            runner.configure(overrideModel);
            runner.prompt("fourth task");
            assertSelection(server.request(3), "child-model", "max");

            SubagentManager.ChildConfiguration providerDefault = new SubagentManager.ChildConfiguration(
                    "child-1", "Worker", null, "default", PermissionMode.ASK);
            runner.configure(providerDefault);
            runner.prompt("fifth task");
            JsonNode defaultEffort = server.request(4);
            assertEquals("parent-two", defaultEffort.path("model").asText());
            assertFalse(defaultEffort.has("reasoning"), defaultEffort.toString());
        }
    }

    @Test
    void configCopiesAndEffortValidationPreserveTheOptionalValue() {
        AgentConfig base = new AgentConfig("test-key", "http://127.0.0.1/v1", "model-one",
                workspace, 2, PermissionMode.ASK);
        assertNull(base.reasoningEffort());
        assertEquals("high", base.withReasoningEffort(" HIGH ").reasoningEffort());
        assertEquals("model-two", base.withModel(" model-two ").model());
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                AgentConfig.reasoningEffortValues());
        assertThrows(IllegalArgumentException.class, () -> base.withReasoningEffort("automatic"));
    }

    @Test
    void sessionModelIsCheckpointedWithHistoryAndRestoredOnResume() throws Exception {
        try (ResponsesServer server = new ResponsesServer()) {
            SessionStore store = new SessionStore(json, workspace.resolve("sessions"));
            SessionStore.Snapshot saved = store.create(workspace, "saved-model", "instructions");
            ContextBudget budget = new ContextBudget();
            AgentConfig config = new AgentConfig("test-key", server.baseUrl(), "configured-model",
                    workspace, 2, PermissionMode.ASK, budget.requestTokenBudget(), budget.triggerPercent(),
                    budget.imageTokenReserve(), "medium");
            SessionRuntime session = SessionRuntime.start(agent(client(config)), store, workspace,
                    config.model(), "instructions", saved.id());

            assertEquals("saved-model", session.model());
            assertEquals("medium", session.reasoningEffort());
            session.setModel("session-only-model");
            assertEquals("saved-model", store.load(saved.id()).model(),
                    "changing a setting alone should not write session metadata");

            session.prompt("continue with the new model");
            assertEquals("session-only-model", server.request(0).path("model").asText());
            assertEquals("session-only-model", store.load(saved.id()).model(),
                    "the next history checkpoint should record the model used for that turn");

            session.setModel("uncheckpointed-model");
            session.resume(saved.id(), workspace);
            assertEquals("session-only-model", session.model());
            assertEquals("medium", session.reasoningEffort(),
                    "resuming a session restores its model and keeps the active effort selection");
        }
    }

    private Agent agent(OpenAiResponsesClient client) {
        return new Agent(json, client, List.of(), (tool, arguments) -> false,
                new PrintStream(OutputStream.nullOutputStream()), 2, "instructions");
    }

    private OpenAiResponsesClient client(AgentConfig config) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new OpenAiResponsesClient(json, http, config, ignored -> { });
    }

    private static void assertSelection(JsonNode request, String model, String effort) {
        assertEquals(model, request.path("model").asText());
        assertEquals(effort, request.path("reasoning").path("effort").asText());
    }

    private final class ResponsesServer implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new ArrayList<>();

        ResponsesServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", this::respond);
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }

        synchronized JsonNode request(int index) { return requests.get(index).deepCopy(); }

        private void respond(HttpExchange exchange) {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                synchronized (this) { requests.add(request.deepCopy()); }
                String response = "{\"id\":\"response-test\",\"status\":\"completed\","
                        + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"output_text\",\"text\":\"summary\"}]}]}";
                boolean streaming = exchange.getRequestHeaders().getFirst("Accept").contains("text/event-stream");
                String payload = streaming
                        ? "data: {\"type\":\"response.completed\",\"response\":" + response + "}\n\n"
                        : response;
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            } catch (Exception failure) {
                exchange.close();
            }
        }

        @Override public void close() { server.stop(0); }
    }
}
