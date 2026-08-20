package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainAcpStartupParityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void missingApiKeyIsAJsonRpcInitializeError() throws Exception {
        Path state = workspace.resolve("missing-key-state");
        Result result = run(state, Map.of(), request(1, "initialize", "{\"protocolVersion\":1}") + "\n");

        assertEquals(0, result.code);
        JsonNode response = json.readTree(result.stdout);
        assertEquals(1, response.path("id").asInt());
        assertEquals(-32600, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("API key"));
        assertEquals("", result.stderr);
        assertFalse(Files.exists(state));
    }

    @Test
    void sessionListLeavesAnEmptyStateRootUnchanged() throws Exception {
        Path state = workspace.resolve("list-state");
        Result result = run(state, Map.of("OPENAI_API_KEY", "test-key"),
                request(1, "initialize", "{\"protocolVersion\":1}") + "\n"
                        + request(2, "session/list", "{}") + "\n");

        List<JsonNode> responses = Arrays.stream(result.stdout.split("\\R"))
                .filter(line -> !line.isBlank()).map(line -> {
                    try { return json.readTree(line); } catch (Exception failure) { throw new RuntimeException(failure); }
                }).collect(Collectors.toUnmodifiableList());
        assertEquals(2, responses.size());
        assertTrue(responses.get(1).path("result").path("sessions").isArray());
        assertEquals(0, responses.get(1).path("result").path("sessions").size());
        assertEquals("", result.stderr);
        assertFalse(Files.exists(state));
    }

    private Result run(Path state, Map<String, String> environment, String input) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"--workspace", workspace.toString(), "--session-root",
                        state.toString(), "acp"}, environment,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout), new PrintStream(stderr));
        return new Result(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private static final class Result {
        private final int code;
        private final String stdout;
        private final String stderr;

        private Result(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int code() { return code; }
        public String stdout() { return stdout; }
        public String stderr() { return stderr; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            Result that = (Result) other;
            return code == that.code
                    && Objects.equals(stdout, that.stdout)
                    && Objects.equals(stderr, that.stderr);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(code);
            result = 31 * result + Objects.hashCode(stdout);
            result = 31 * result + Objects.hashCode(stderr);
            return result;
        }

        @Override
        public String toString() {
            return "Result[code=" + code + ", stdout=" + stdout + ", stderr=" + stderr + "]";
        }
    }
}
