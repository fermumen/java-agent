package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content-addressed image sidecars keep stateless session JSON compact. */
final class SessionImages {
    private static final String REF_PREFIX = "java-agent-image:";
    private static final Pattern DATA_URL = Pattern.compile(
            "^data:(image/(?:png|jpeg|gif|webp));base64,(.*)$", Pattern.DOTALL);
    private static final Pattern SAFE_FILE = Pattern.compile("([a-f0-9]{64})\\.(png|jpg|gif|webp)");
    private static final int MAX_REFERENCES = 512;

    private SessionImages() { }

    static ArrayNode externalize(Path sessionDirectory, ArrayNode input) throws IOException {
        ArrayNode copy = input.deepCopy();
        int[] count = {0};
        transform(sessionDirectory, copy, false, count);
        return copy;
    }

    static ArrayNode hydrate(Path sessionDirectory, ArrayNode input) throws IOException {
        ArrayNode copy = input.deepCopy();
        int[] count = {0};
        transform(sessionDirectory, copy, true, count);
        return copy;
    }

    static List<String> referencedFiles(JsonNode input) throws IOException {
        Set<String> references = new LinkedHashSet<>();
        collectReferences(input, references);
        return references.stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    static void verifySidecars(Path sessionDirectory, Iterable<String> filenames) throws IOException {
        for (String filename : filenames) load(sessionDirectory, filename);
    }

    static void deleteSidecars(Path sessionDirectory) throws IOException {
        Path images = sessionDirectory.resolve("images");
        if (!Files.exists(images, LinkOption.NOFOLLOW_LINKS)) return;
        rejectSymlink(images);
        if (!Files.isDirectory(images, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Session images path is not a directory: " + images);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(images)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!SAFE_FILE.matcher(name).matches() || Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsafe file in session images directory: " + file);
                }
                Files.delete(file);
            }
        }
        Files.delete(images);
    }

    private static void transform(Path sessionDirectory, JsonNode node,
                                  boolean hydrate, int[] count) throws IOException {
        if (node.isArray()) {
            for (JsonNode child : node) transform(sessionDirectory, child, hydrate, count);
            return;
        }
        if (!(node instanceof ObjectNode)) return;
        ObjectNode object = (ObjectNode) node;
        if (object.path("type").asText().equals("input_image") && object.path("image_url").isTextual()) {
            String url = object.path("image_url").asText();
            boolean candidate = hydrate ? url.startsWith(REF_PREFIX) : url.startsWith("data:image/");
            if (candidate) {
                if (++count[0] > MAX_REFERENCES) throw new IOException("Session contains too many image references");
                object.put("image_url", hydrate
                        ? load(sessionDirectory, url.substring(REF_PREFIX.length()))
                        : store(sessionDirectory, url));
            }
        }
        var fields = object.properties().iterator();
        while (fields.hasNext()) transform(sessionDirectory, fields.next().getValue(), hydrate, count);
    }

    private static void collectReferences(JsonNode node, Set<String> references) throws IOException {
        if (node.isArray()) {
            for (JsonNode child : node) collectReferences(child, references);
            return;
        }
        if (!(node instanceof ObjectNode)) return;
        ObjectNode object = (ObjectNode) node;
        if (object.path("type").asText().equals("input_image") && object.path("image_url").isTextual()) {
            String url = object.path("image_url").asText();
            if (url.startsWith(REF_PREFIX)) {
                String filename = url.substring(REF_PREFIX.length());
                if (!SAFE_FILE.matcher(filename).matches()) {
                    throw new IOException("Unsafe session image reference: " + filename);
                }
                if (references.add(filename) && references.size() > MAX_REFERENCES) {
                    throw new IOException("Session contains too many image references");
                }
            }
        }
        var fields = object.properties().iterator();
        while (fields.hasNext()) collectReferences(fields.next().getValue(), references);
    }

    private static String store(Path sessionDirectory, String dataUrl) throws IOException {
        Matcher match = DATA_URL.matcher(dataUrl);
        if (!match.matches()) throw new IOException("Unsupported session image data URL");
        String mediaType = match.group(1).toLowerCase(Locale.ROOT);
        String encoded = match.group(2);
        int maxEncoded = ((ImageAttachment.MAX_IMAGE_BYTES + 2) / 3) * 4;
        if (encoded.length() > maxEncoded) throw new IOException("Session image exceeds the 20 MiB limit");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Session image has invalid Base64", invalid);
        }
        validateBytes(bytes, mediaType);
        String digest = sha256(bytes);
        String filename = digest + extension(mediaType);
        Path images = sessionDirectory.resolve("images");
        createDirectory(images);
        Path target = images.resolve(filename);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(target);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) > ImageAttachment.MAX_IMAGE_BYTES
                    || !MessageDigest.isEqual(bytes, Files.readAllBytes(target))) {
                throw new IOException("Session image digest collision: " + filename);
            }
        } else {
            atomicWrite(target, bytes);
        }
        return REF_PREFIX + filename;
    }

    private static String load(Path sessionDirectory, String filename) throws IOException {
        Matcher match = SAFE_FILE.matcher(filename);
        if (!match.matches()) throw new IOException("Unsafe session image reference: " + filename);
        Path images = sessionDirectory.resolve("images");
        rejectSymlink(images);
        Path file = images.resolve(filename);
        rejectSymlink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing session image: " + filename);
        }
        long size = Files.size(file);
        if (size > ImageAttachment.MAX_IMAGE_BYTES) throw new IOException("Session image exceeds the 20 MiB limit");
        byte[] bytes = Files.readAllBytes(file);
        if (!sha256(bytes).equals(match.group(1))) throw new IOException("Session image digest mismatch: " + filename);
        String mediaType = ImageAttachment.detectMediaType(bytes);
        if (mediaType == null || !extension(mediaType).substring(1).equals(match.group(2))) {
            throw new IOException("Session image type mismatch: " + filename);
        }
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static void validateBytes(byte[] bytes, String mediaType) throws IOException {
        if (bytes.length > ImageAttachment.MAX_IMAGE_BYTES) throw new IOException("Session image exceeds the 20 MiB limit");
        if (!mediaType.equals(ImageAttachment.detectMediaType(bytes))) {
            throw new IOException("Session image MIME does not match its bytes");
        }
    }

    private static String extension(String mediaType) throws IOException {
        switch (mediaType) {
            case "image/png":
                return ".png";
            case "image/jpeg":
                return ".jpg";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            default:
                throw new IOException("Unsupported session image type: " + mediaType);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void createDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Session images path is not a directory: " + directory);
            }
        } else {
            Files.createDirectory(directory);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Session image path is a symlink: " + path);
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".image-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
