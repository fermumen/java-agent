package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;

/** Immutable, bounded local image input using fx's magic-byte contract. */
record ImageAttachment(Path path, String mediaType, byte[] bytes) {
    static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    static final int MAX_IMAGES_PER_PROMPT = 20;

    ImageAttachment {
        path = path.toAbsolutePath().normalize();
        bytes = bytes.clone();
        if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("image exceeds the 20 MiB limit");
        String detected = detectMediaType(bytes);
        if (detected == null || !detected.equals(mediaType)) {
            throw new IllegalArgumentException("unsupported image type: " + path);
        }
    }

    @Override public byte[] bytes() { return bytes.clone(); }

    static ImageAttachment load(Path workspace, String input) throws IOException {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("image path must not be blank");
        String normalized = normalize(input);
        Path supplied = Path.of(normalized);
        Path resolved = (supplied.isAbsolute() ? supplied : workspace.resolve(supplied)).toRealPath();
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(resolved)) {
            throw new IOException("image is not a regular file: " + input);
        }
        long size = Files.size(resolved);
        if (size > MAX_IMAGE_BYTES) throw new IOException("image exceeds the 20 MiB limit: " + input);
        byte[] bytes;
        try (InputStream source = Files.newInputStream(resolved);
             ByteArrayOutputStream kept = new ByteArrayOutputStream((int) Math.min(size, MAX_IMAGE_BYTES))) {
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            for (int count; (count = source.read(buffer)) >= 0;) {
                if (count > MAX_IMAGE_BYTES - total) {
                    throw new IOException("image exceeds the 20 MiB limit: " + input);
                }
                kept.write(buffer, 0, count);
                total += count;
            }
            bytes = kept.toByteArray();
        }
        String mediaType = detectMediaType(bytes);
        if (mediaType == null) throw new IOException("unsupported image type: " + input);
        return new ImageAttachment(resolved, mediaType, bytes);
    }

    ObjectNode inputPart(ObjectMapper json, String detail) {
        return json.createObjectNode().put("type", "input_image")
                .put("image_url", dataUrl()).put("detail", detail);
    }

    String dataUrl() {
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    static String detectMediaType(byte[] value) {
        if (starts(value, new int[]{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (starts(value, new int[]{0xff, 0xd8, 0xff})) return "image/jpeg";
        if (starts(value, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || starts(value, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return "image/gif";
        if (value.length >= 12 && starts(value, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                && value[8] == 'W' && value[9] == 'E' && value[10] == 'B' && value[11] == 'P') return "image/webp";
        return null;
    }

    private static String normalize(String value) {
        String result = value.trim();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.replace("\\ ", " ");
    }

    private static boolean starts(byte[] value, int[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if ((value[index] & 0xff) != prefix[index]) return false;
        }
        return true;
    }

    private static boolean starts(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }
}
