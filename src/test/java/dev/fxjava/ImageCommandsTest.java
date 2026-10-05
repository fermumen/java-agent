package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /image command core: validation matrix, queueing, listing, and clearing. */
class ImageCommandsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void validationMatrixRefusesFriendlyAndLeavesQueueUntouched() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("notes.png"), "not an image");
        Files.createDirectory(workspace.resolve("folder.png"));
        Path oversized = workspace.resolve("oversized.png");
        try (FileChannel file = FileChannel.open(oversized, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10)));
            file.position(ImageAttachment.MAX_IMAGE_BYTES);
            file.write(ByteBuffer.wrap(new byte[]{1}));
        }

        SessionRuntime session = runtime();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream out = stream(output);

        ImageCommands.handle(session, "missing.png", workspace, Ansi.of(false), out);
        ImageCommands.handle(session, "notes.png", workspace, Ansi.of(false), out);
        ImageCommands.handle(session, "folder.png", workspace, Ansi.of(false), out);
        ImageCommands.handle(session, "oversized.png", workspace, Ansi.of(false), out);
        ImageCommands.handle(session, "", workspace, Ansi.of(false), out);

        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\R");
        assertTrue(lines[0].startsWith("image file not found"), lines[0]);
        assertEquals("unsupported image type: notes.png", lines[1]);
        assertTrue(lines[2].startsWith("image file not found"), lines[2]);
        assertTrue(lines[3].contains("20 MiB"), lines[3]);
        assertEquals("no pending images", lines[4]);
        assertEquals(List.of(), session.pendingImages(), "refusals stage nothing");
    }

    @Test
    void attachListAndClearRoundTrip() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.write(workspace.resolve("photo.png"),
                bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1));
        Files.write(workspace.resolve("chart.jpeg"), bytes(0xff, 0xd8, 0xff, 9));

        SessionRuntime session = runtime();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream out = stream(output);

        ImageCommands.handle(session, "photo.png", workspace, Ansi.of(false), out);
        ImageCommands.handle(session, "\"chart.jpeg\"", workspace, Ansi.of(false), out);
        assertTrue(output.toString(StandardCharsets.UTF_8)
                        .contains("attached image: photo.png · 9 B"),
                "attachment confirms basename and size");

        ByteArrayOutputStream listing = new ByteArrayOutputStream();
        ImageCommands.handle(session, "", workspace, Ansi.of(false), stream(listing));
        String listed = listing.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("2 pending"), listed);
        assertTrue(listed.contains(" - photo.png (image/png)"), listed);
        assertTrue(listed.contains(" - chart.jpeg (image/jpeg)"), listed);
        assertEquals(2, session.pendingImages().size());

        ByteArrayOutputStream cleared = new ByteArrayOutputStream();
        ImageCommands.handle(session, "clear", workspace, Ansi.of(false), stream(cleared));
        assertEquals("cleared pending images",
                cleared.toString(StandardCharsets.UTF_8).strip());
        assertTrue(session.pendingImages().isEmpty());

        ByteArrayOutputStream bareAfterClear = new ByteArrayOutputStream();
        ImageCommands.handle(session, null, workspace, Ansi.of(false), stream(bareAfterClear));
        assertEquals("no pending images",
                bareAfterClear.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void stagingRejectsMoreThanThePerPromptLimit() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.write(workspace.resolve("tiny.png"), bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10));
        SessionRuntime session = runtime();
        for (int index = 0; index < ImageAttachment.MAX_IMAGES_PER_PROMPT; index++) {
            session.stagePendingImage(ImageAttachment.load(workspace, "tiny.png"));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageCommands.handle(session, "tiny.png", workspace, Ansi.of(false), stream(output));
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("at most " + ImageAttachment.MAX_IMAGES_PER_PROMPT));
        assertEquals(ImageAttachment.MAX_IMAGES_PER_PROMPT, session.pendingImages().size());
    }

    @Test
    void submissionDrainsPendingImagesIntoAMultimodalMessage() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.write(workspace.resolve("tiny.png"), bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10, 7));
        SessionRuntime session = runtime();
        ImageCommands.handle(session, "tiny.png", workspace, Ansi.of(false),
                stream(new ByteArrayOutputStream()));

        session.prompt("describe this");
        assertTrue(session.pendingImages().isEmpty(), "submit clears the queue");

        ByteArrayOutputStream afterFailure = new ByteArrayOutputStream();
        ImageCommands.handle(session, "clear", workspace, Ansi.of(false), stream(afterFailure));
        assertEquals("no pending images",
                afterFailure.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void humanBytesStaysReadableAcrossMagnitudes() {
        assertEquals("512 B", ImageCommands.humanBytes(512));
        assertEquals("1.0 KiB", ImageCommands.humanBytes(1024));
        assertEquals("1.2 MiB", ImageCommands.humanBytes(Math.round(1.2 * 1024 * 1024)));
    }

    private SessionRuntime runtime() throws Exception {
        ObjectNode reply = json.createObjectNode().put("id", "resp").put("status", "completed");
        ObjectNode message = reply.putArray("output").addObject();
        message.put("type", "message").put("role", "assistant").put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text")
                .put("text", "ok").putArray("annotations");
        Agent agent = new Agent(json, (input, tools, instructions) -> reply.deepCopy(),
                List.of(), (tool, arguments) -> false,
                new PrintStream(new ByteArrayOutputStream()), 5, "system");
        return SessionRuntime.start(agent, null, temporary, "model", "system", null);
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }
}
