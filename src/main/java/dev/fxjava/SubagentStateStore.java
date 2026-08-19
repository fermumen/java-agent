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

    private void rejectDirectory() throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe subagent state directory: " + directory);
        }
    }
}
