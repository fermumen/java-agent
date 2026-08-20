package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Session-scoped sidecar store for fx-compatible large tool-result previews. */
final class ToolResultStore {
    static final int LARGE_RESULT_BYTES = 16 * 1024;
    static final int PREVIEW_BYTES = 4 * 1024;
    static final int READ_DEFAULT_BYTES = 8 * 1024;
    static final int READ_MAX_BYTES = 64 * 1024;
    private static final int STORED_MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REFERENCES = 512;
    private static final Pattern HANDLE = Pattern.compile(
            "result-[A-Za-z0-9_-]{1,48}-([0-9a-f]{16})-([0-9a-f]{16})\\.txt");
    private static final Pattern HANDLE_REFERENCE = Pattern.compile(
            "<tool_result_handle>(result-[A-Za-z0-9_-]{1,48}-[0-9a-f]{16}-[0-9a-f]{16}\\.txt)</tool_result_handle>");

    private final Path sessionRoot;
    private final Map<String, byte[]> ephemeral = new LinkedHashMap<>();
    private String sessionId;

    ToolResultStore(Path sessionRoot) {
        this.sessionRoot = sessionRoot.toAbsolutePath().normalize();
    }

    synchronized void setSession(String id) {
        if (id != null && !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("unsafe result session id");
        }
        sessionId = id;
        ephemeral.clear();
    }

    synchronized String prepare(String callId, String toolName, String output) throws IOException {
        String redacted = SecretRedactor.mask(output);
        byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= LARGE_RESULT_BYTES) return redacted;
        if (bytes.length > STORED_MAX_BYTES) {
            bytes = Arrays.copyOf(bytes, utf8BackwardBoundary(bytes, STORED_MAX_BYTES));
        }
        String handle = handle(callId, toolName, bytes);
        store(handle, bytes);
        int previewEnd = utf8BackwardBoundary(bytes, Math.min(PREVIEW_BYTES, bytes.length));
        String preview = new String(bytes, 0, previewEnd, StandardCharsets.UTF_8);
        return "<tool_result_preview handle=\"" + handle + "\" stored_bytes=\"" + bytes.length + "\">\n"
                + preview + "\n</tool_result_preview>\n<tool_result_handle>" + handle
                + "</tool_result_handle>\nFull redacted result is stored outside session JSON. Use read_tool_result "
                + "with this handle to inspect a byte range or literal query.";
    }

    synchronized String read(String rawHandle, int startByte, int byteCount, String query) throws IOException {
        String handle = validate(rawHandle.trim());
        byte[] bytes = load(handle);
        verifyDigest(handle, bytes);
        if (query != null) return search(handle, bytes, query);
        int start = Math.min(Math.max(0, startByte - 1), bytes.length);
        int end = Math.min(bytes.length, start + Math.min(byteCount, READ_MAX_BYTES));
        start = utf8ForwardBoundary(bytes, start);
        end = utf8BackwardBoundary(bytes, end);
        String text = new String(bytes, start, Math.max(0, end - start), StandardCharsets.UTF_8);
        return "<tool_result handle=\"" + handle + "\" start_byte=\"" + (start + 1)
                + "\" end_byte=\"" + end + "\" total_bytes=\"" + bytes.length + "\">\n"
                + text + "\n</tool_result>";
    }

    static List<String> referencedHandles(JsonNode input) throws IOException {
        Set<String> references = new LinkedHashSet<>();
        collectReferences(input, references);
        return references.stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    static void verifySidecars(Path session, Collection<String> handles) throws IOException {
        for (String handle : handles) verifySidecar(session, validate(handle));
    }

    static void copySidecars(Path sourceSession, Path targetSession,
                             Collection<String> handles) throws IOException {
        if (handles.isEmpty()) return;
        Path source = sourceSession.resolve("tool-results");
        rejectDirectory(source);
        Path target = targetSession.resolve("tool-results");
        Files.createDirectories(target);
        rejectDirectory(target);
        for (String rawHandle : handles) {
            String handle = validate(rawHandle);
            Path file = verifySidecar(sourceSession, handle);
            Files.copy(file, target.resolve(handle), StandardCopyOption.REPLACE_EXISTING);
            verifySidecar(targetSession, handle);
        }
    }

    static void deleteSidecars(Path sessionDirectory) throws IOException {
        Path directory = sessionDirectory.resolve("tool-results");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        rejectDirectory(directory);
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                validate(file.getFileName().toString());
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("unsafe tool-result sidecar: " + file);
                }
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }

    private String search(String handle, byte[] bytes, String rawQuery) {
        String query = rawQuery.trim();
        if (query.isEmpty()) throw new IllegalArgumentException("InvalidQuery");
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder("<tool_result_query handle=\"").append(handle)
                .append("\">\nquery: \"").append(jsonEscape(query)).append("\"\n");
        int matches = 0;
        String[] lines = text.split("\\n", -1);
        for (int index = 0; index < lines.length && matches < 50; index++) {
            if (!lines[index].contains(query)) continue;
            String addition = (index + 1) + "|" + lines[index] + "\n";
            if (output.toString().getBytes(StandardCharsets.UTF_8).length
                    + addition.getBytes(StandardCharsets.UTF_8).length > READ_MAX_BYTES) break;
            output.append(addition);
            matches++;
        }
        if (matches == 0) output.append("(no matches)\n");
        return output.append("</tool_result_query>").toString();
    }

    private void store(String handle, byte[] bytes) throws IOException {
        if (sessionId == null) {
            ephemeral.put(handle, bytes.clone());
            return;
        }
        Path directory = resultDirectory();
        Files.createDirectories(directory);
        rejectDirectory(directory);
        Path target = directory.resolve(handle);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(Files.readAllBytes(target), bytes)) throw new IOException("tool-result handle collision");
            return;
        }
        Path temporary = Files.createTempFile(directory, ".result-", ".tmp");
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

    private byte[] load(String handle) throws IOException {
        if (sessionId == null) {
            byte[] bytes = ephemeral.get(handle);
            if (bytes == null) throw missing(handle);
            return bytes.clone();
        }
        Path directory = resultDirectory();
        rejectDirectory(directory);
        Path file = directory.resolve(handle);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw missing(handle);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > STORED_MAX_BYTES) throw new IOException("unsafe tool-result sidecar");
        return Files.readAllBytes(file);
    }

    private Path resultDirectory() throws IOException {
        Path sessions = sessionRoot.resolve("sessions");
        Path directory = sessions.resolve(sessionId).resolve("tool-results").normalize();
        if (!directory.startsWith(sessions)) throw new IOException("unsafe result session path");
        return directory;
    }

    private static String handle(String callId, String toolName, byte[] bytes) {
        String safeTool = toolName.replaceAll("[^A-Za-z0-9_-]", "-");
        if (safeTool.isEmpty()) safeTool = "call";
        if (safeTool.length() > 48) safeTool = safeTool.substring(0, 48);
        return "result-" + safeTool + "-" + digest(callId.getBytes(StandardCharsets.UTF_8)).substring(0, 16)
                + "-" + digest(bytes).substring(0, 16) + ".txt";
    }

    private static String validate(String handle) {
        if (handle.length() > 160 || handle.contains("..") || !HANDLE.matcher(handle).matches()) {
            throw new IllegalArgumentException("InvalidHandle");
        }
        return handle;
    }

    private static void verifyDigest(String handle, byte[] bytes) throws IOException {
        Matcher match = HANDLE.matcher(handle);
        if (!match.matches() || !digest(bytes).startsWith(match.group(2))) {
            throw new IOException("tool-result digest mismatch");
        }
    }

    private static Path verifySidecar(Path session, String handle) throws IOException {
        Path directory = session.resolve("tool-results");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing session tool result: " + handle);
        }
        rejectDirectory(directory);
        Path file = directory.resolve(handle);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing session tool result: " + handle);
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > STORED_MAX_BYTES) {
            throw new IOException("unsafe tool-result sidecar: " + handle);
        }
        verifyDigest(handle, Files.readAllBytes(file));
        return file;
    }

    private static void collectReferences(JsonNode node, Set<String> references) throws IOException {
        if (node.isTextual()) {
            Matcher matcher = HANDLE_REFERENCE.matcher(node.asText());
            while (matcher.find()) {
                String handle = validate(matcher.group(1));
                if (references.add(handle) && references.size() > MAX_REFERENCES) {
                    throw new IOException("Session contains too many tool-result references");
                }
            }
            return;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) collectReferences(child, references);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int utf8BackwardBoundary(byte[] bytes, int index) {
        int value = Math.min(index, bytes.length);
        while (value > 0 && value < bytes.length && (bytes[value] & 0xc0) == 0x80) value--;
        return value;
    }

    private static int utf8ForwardBoundary(byte[] bytes, int index) {
        int value = Math.min(index, bytes.length);
        while (value < bytes.length && (bytes[value] & 0xc0) == 0x80) value++;
        return value;
    }

    private static void rejectDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe tool-result directory: " + directory);
        }
    }

    private static IOException missing(String handle) {
        return new IOException("read_tool_result failed for handle " + handle
                + ": ResultHandleNotFound. No exact match exists in the active tool-result store; "
                + "handles are session-scoped and must be copied exactly from the tool result preview.");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
