package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Image snapshot cases ported from fx session/image persistence boundaries. */
class SessionImagesTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void externalizesDeduplicatesHydratesRecoversAndDeletes() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path root = temporary.resolve("state");
        SessionStore store = new SessionStore(json, root);
        SessionStore.Snapshot session = store.create(workspace, "model", "system");
        String dataUrl = dataUrl(png(64));
        ArrayNode input = imageHistory(dataUrl, dataUrl);

        session = store.update(session, input, "system");
        String sessionId = session.id();
        Path directory = root.resolve("sessions").resolve(session.id());
        String persisted = Files.readString(directory.resolve("session.json"));
        assertFalse(persisted.contains("data:image/png;base64,"));
        assertTrue(persisted.contains("java-agent-image:"));
        try (var files = Files.list(directory.resolve("images"))) {
            assertEquals(1, files.count());
        }
        assertEquals(dataUrl, store.load(sessionId).input().path(0).path("content").path(0)
                .path("image_url").asText());

        SessionStore.Snapshot recovered = store.recover(session.id());
        assertNotEquals(session.id(), recovered.id());
        assertEquals(session.input(), recovered.input());
        assertTrue(Files.isDirectory(root.resolve("sessions").resolve(recovered.id()).resolve("images")));
        store.delete(recovered.id());
        assertFalse(Files.exists(root.resolve("sessions").resolve(recovered.id())));
        assertEquals(dataUrl, store.load(sessionId).input().path(0).path("content").path(0)
                .path("image_url").asText());
    }

    @Test
    void compactSidecarAllowsAnImageLargerThanTheJsonSnapshotLimit() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("large-workspace"));
        Path root = temporary.resolve("large-state");
        SessionStore store = new SessionStore(json, root);
        SessionStore.Snapshot session = store.create(workspace, "model", "system");
        byte[] bytes = png(7 * 1024 * 1024);
        String url = dataUrl(bytes);

        session = store.update(session, imageHistory(url), "system");
        String sessionId = session.id();
        Path file = root.resolve("sessions").resolve(session.id()).resolve("session.json");
        assertTrue(Files.size(file) < 8 * 1024 * 1024);
        assertEquals(url, store.load(sessionId).input().path(0).path("content").path(0)
                .path("image_url").asText());
    }

    @Test
    void rejectsInvalidMimeBase64AndTamperedContent() throws Exception {
        Path directory = Files.createDirectories(temporary.resolve("standalone"));
        assertThrows(IOException.class, () -> SessionImages.externalize(directory,
                imageHistory("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(png(8)))));
        assertThrows(IOException.class, () -> SessionImages.externalize(directory,
                imageHistory("data:image/png;base64,%%%")));

        Path workspace = Files.createDirectory(temporary.resolve("tamper-workspace"));
        Path root = temporary.resolve("tamper-state");
        SessionStore store = new SessionStore(json, root);
        SessionStore.Snapshot session = store.create(workspace, "model", "system");
        session = store.update(session, imageHistory(dataUrl(png(16))), "system");
        String sessionId = session.id();
        Path images = root.resolve("sessions").resolve(sessionId).resolve("images");
        Path sidecar;
        try (var files = Files.list(images)) {
            sidecar = files.findFirst().orElseThrow();
        }
        Files.write(sidecar, png(17));
        assertTrue(assertThrows(IOException.class, () -> store.load(sessionId))
                .getMessage().contains("digest mismatch"));
    }

    @Test
    void rejectsSymlinkSubstitutionWhenSupported() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("link-workspace"));
        Path root = temporary.resolve("link-state");
        SessionStore store = new SessionStore(json, root);
        SessionStore.Snapshot session = store.create(workspace, "model", "system");
        session = store.update(session, imageHistory(dataUrl(png(16))), "system");
        String sessionId = session.id();
        Path images = root.resolve("sessions").resolve(sessionId).resolve("images");
        Path sidecar;
        try (var files = Files.list(images)) {
            sidecar = files.findFirst().orElseThrow();
        }
        Path replacement = temporary.resolve("replacement.png");
        Files.write(replacement, png(16));
        try {
            Files.delete(sidecar);
            Files.createSymbolicLink(sidecar, replacement);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        assertTrue(assertThrows(IOException.class, () -> store.load(sessionId))
                .getMessage().contains("symlink"));
    }

    private ArrayNode imageHistory(String... urls) {
        ArrayNode input = json.createArrayNode();
        var content = input.addObject().put("role", "user").putArray("content");
        for (String url : urls) {
            content.addObject().put("type", "input_image").put("image_url", url).put("detail", "auto");
        }
        return input;
    }

    private static String dataUrl(byte[] bytes) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] png(int size) {
        byte[] bytes = new byte[Math.max(size, 8)];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }
}
