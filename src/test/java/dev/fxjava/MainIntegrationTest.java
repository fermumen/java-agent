package dev.fxjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainIntegrationTest {
    @TempDir
    Path workspace;

    @Test
    void runsResponsesToolLoopThroughTheCli() throws Exception {
        try (FakeResponsesServer api = new FakeResponsesServer(0)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();

            int exitCode = Main.run(new String[]{
                            "--base-url", api.baseUrl(),
                            "--workspace", workspace.toString(),
                            "--yes", "Create the smoke marker"
                    }, Map.of("OPENAI_API_KEY", "test-key"),
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    new PrintStream(errors, true, StandardCharsets.UTF_8));

            assertEquals(0, exitCode);
            assertEquals(2, api.requestCount());
            assertEquals("created by responses smoke test\n",
                    Files.readString(workspace.resolve("smoke.txt")));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Responses smoke test complete"));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("[tool] write smoke.txt"));
        }
    }
}
