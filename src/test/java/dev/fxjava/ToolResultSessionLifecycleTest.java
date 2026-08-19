package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultSessionLifecycleTest {
    private static final Pattern HANDLE = Pattern.compile("<tool_result_handle>([^<]+)</tool_result_handle>");

    @TempDir
    Path root;

    @Test
    void recoveryCopiesAndDeletionRemovesOnlyValidatedResultSidecars() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        SessionStore sessions = new SessionStore(new ObjectMapper(), root.resolve("state"));
        SessionStore.Snapshot source = sessions.create(workspace, "model", "instructions");
        ToolResultStore results = new ToolResultStore(root.resolve("state"));
        results.setSession(source.id());
        String prepared = results.prepare("call", "large", "needle\n" + "x".repeat(20_000));
        Matcher matcher = HANDLE.matcher(prepared);
        assertTrue(matcher.find());
        String handle = matcher.group(1);

        SessionStore.Snapshot recovered = sessions.recover(source.id());
        results.setSession(recovered.id());
        assertTrue(results.read(handle, 1, 32, null).contains("needle"));

        Path recoveredDirectory = root.resolve("state").resolve("sessions").resolve(recovered.id());
        sessions.delete(recovered.id());
        assertFalse(Files.exists(recoveredDirectory));
        results.setSession(source.id());
        assertTrue(results.read(handle, 1, 32, null).contains("needle"));
    }
}
