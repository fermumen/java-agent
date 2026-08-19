package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Atomic, symlink-rejecting persistence for process-recoverable child control state. */
final class SubagentStateStore {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper json;
    private final Path directory;

    SubagentStateStore(ObjectMapper json, Path stateRoot) {
        this.json = json;
        this.directory = stateRoot == null ? null
                : stateRoot.toAbsolutePath().normalize().resolve("subagents");
    }

    boolean enabled() { return directory != null; }

    List<ObjectNode> load() throws IOException {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectDirectory();
        List<ObjectNode> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (file.getFileName().toString().equals("_operations.json")) continue;
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) > MAX_BYTES) continue;
                try {
                    JsonNode decoded = json.readTree(Files.readAllBytes(file));
                    if (decoded instanceof ObjectNode object && object.path("schema_version").asInt() == 1) {
                        result.add(object);
                    }
                } catch (Exception corrupt) {
                    // Corrupt records remain isolated from other children.
                }
            }
        }
        return List.copyOf(result);
    }

    List<Operation> loadOperations() throws IOException {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectDirectory();
        Path file = directory.resolve("_operations.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
            return List.of();
        }
        try {
            JsonNode root = json.readTree(Files.readAllBytes(file));
            if (!root.isObject() || root.path("schema_version").asInt() != 1
                    || !root.path("operations").isArray() || root.path("operations").size() > 256) {
                return List.of();
            }
            List<Operation> result = new ArrayList<>();
            for (JsonNode item : root.path("operations")) {
                String id = item.path("operation_id").asText();
                String fingerprint = item.path("request_fingerprint").asText();
                String receipt = item.path("receipt").asText();
                if (id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isWhitespace)
                        || !fingerprint.matches("[0-9a-f]{64}")
                        || receipt.isBlank() || receipt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200_000) {
                    return List.of();
                }
                result.add(new Operation(id, fingerprint, receipt));
            }
            return List.copyOf(result);
        } catch (Exception corrupt) {
            return List.of();
        }
    }

    void saveOperations(List<Operation> operations) throws IOException {
        if (directory == null) return;
        if (operations.size() > 256) throw new IOException("subagent operation ledger exceeds 256 entries");
        Files.createDirectories(directory);
        rejectDirectory();
        ObjectNode root = json.createObjectNode().put("schema_version", 1);
        var values = root.putArray("operations");
        for (Operation operation : operations) {
            values.addObject().put("operation_id", operation.operationId())
                    .put("request_fingerprint", operation.requestFingerprint())
                    .put("receipt", operation.receipt());
        }
        saveFile(directory.resolve("_operations.json"), root);
    }

    void save(String id, ObjectNode value) throws IOException {
        if (directory == null) return;
        SubagentCommand.validateId(id);
        Files.createDirectories(directory);
        rejectDirectory();
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > MAX_BYTES) throw new IOException("subagent state exceeds 8 MiB");
        Path target = directory.resolve(id + ".json");
        Path temporary = Files.createTempFile(directory, ".subagent-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void saveFile(Path target, JsonNode value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > MAX_BYTES) throw new IOException("subagent state exceeds 8 MiB");
        Path temporary = Files.createTempFile(directory, ".subagent-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record Operation(String operationId, String requestFingerprint, String receipt) { }

    private void rejectDirectory() throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe subagent state directory: " + directory);
        }
    }
}
