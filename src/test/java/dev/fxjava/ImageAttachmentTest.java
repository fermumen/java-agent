package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relevant loader cases ported from fx/core/images/image_attachments.zig. */
class ImageAttachmentTest {
    @TempDir
    Path workspace;

    @Test
    void detectsSupportedTypesFromBytesRatherThanExtensions() throws Exception {
        assertType("image/png", "renamed.bin", bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1));
        assertType("image/jpeg", "photo.data", bytes(0xff, 0xd8, 0xff, 1));
        assertType("image/gif", "animation.data", "GIF89arest".getBytes());
        assertType("image/webp", "web.data", "RIFFxxxxWEBPrest".getBytes());
    }

    @Test
    void resolvesRelativeQuotedAndFinderEscapedPaths() throws Exception {
        Path image = workspace.resolve("Clean Shot.png");
        Files.write(image, bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10));
        assertEquals(image.toRealPath(), ImageAttachment.load(workspace, "\"Clean Shot.png\"").path());
        assertEquals(image.toRealPath(), ImageAttachment.load(workspace, "Clean\\ Shot.png").path());
    }

    @Test
    void rejectsMissingUnsupportedDirectoriesAndProviderLimitPlusOne() throws Exception {
        assertThrows(IOException.class, () -> ImageAttachment.load(workspace, "missing.png"));
        Files.writeString(workspace.resolve("plain.png"), "not an image");
        assertThrows(IOException.class, () -> ImageAttachment.load(workspace, "plain.png"));
        Files.createDirectory(workspace.resolve("folder.png"));
        assertThrows(IOException.class, () -> ImageAttachment.load(workspace, "folder.png"));

        Path oversized = workspace.resolve("oversized.png");
        try (FileChannel file = FileChannel.open(oversized, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10)));
            file.position(ImageAttachment.MAX_IMAGE_BYTES);
            file.write(ByteBuffer.wrap(new byte[]{1}));
        }
        IOException tooLarge = assertThrows(IOException.class,
                () -> ImageAttachment.load(workspace, "oversized.png"));
        assertTrue(tooLarge.getMessage().contains("20 MiB"));
    }

    @Test
    void rendersOfficialResponsesInputImageDataUrl() throws Exception {
        Path image = workspace.resolve("tiny.png");
        Files.write(image, bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10, 42));
        var part = ImageAttachment.load(workspace, "tiny.png").inputPart(new ObjectMapper(), "auto");
        assertEquals("input_image", part.path("type").asText());
        assertEquals("auto", part.path("detail").asText());
        assertTrue(part.path("image_url").asText().startsWith("data:image/png;base64,"));
    }

    private void assertType(String expected, String name, byte[] content) throws Exception {
        Files.write(workspace.resolve(name), content);
        assertEquals(expected, ImageAttachment.load(workspace, name).mediaType());
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) result[index] = (byte) values[index];
        return result;
    }
}
