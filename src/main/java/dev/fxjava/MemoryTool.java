package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/** Small persistent memory tool ported from fx's save/list/clear owner. */
final class MemoryTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    private final Path stateRoot;
    private final Path path;
    private final ObjectNode parameters;

    MemoryTool(Path stateRoot) {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        this.path = this.stateRoot.resolve("memories.json");
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode action = properties.putObject("action").put("type", "string");
        action.putArray("enum").add("save").add("list").add("clear");
        properties.putObject("fact").put("type", "string");
        schema.putArray("required").add("action");
        schema.put("additionalProperties", false);
        parameters = schema;
    }

    @Override public String name() { return "memory"; }
    @Override public String description() { return "Save, list, or clear durable facts for future sessions."; }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public boolean requiresApproval(JsonNode arguments) {
        String action = arguments.path("action").asText();
        return action.equals("save") || action.equals("clear");
    }

    @Override
    public String preview(JsonNode arguments) {
        String action = arguments.path("action").asText("?");
        String fact = arguments.path("fact").asText();
        return "memory " + action + (fact.isBlank() ? "" : " `" + abbreviate(fact, 120) + "`");
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        validateShape(arguments);
        String action = arguments.path("action").asText();
        return switch (action) {
            case "save" -> save(arguments);
            case "list" -> list();
            case "clear" -> clear();
            default -> throw new IllegalArgumentException("memory field \"action\" must be one of: save, list, clear");
        };
    }

    private String save(JsonNode arguments) throws IOException {
        if (!arguments.path("fact").isTextual()) return "no fact provided";
        String fact = arguments.path("fact").asText();
        Set<String> facts = load();
        if (facts.add(fact)) write(facts);
        return "remembered";
    }

    private String list() throws IOException {
        Set<String> facts = load();
        if (facts.isEmpty()) return "No saved memories";
        StringBuilder output = new StringBuilder();
        facts.forEach(fact -> output.append("- ").append(fact).append('\n'));
        return output.toString();
    }

    private String clear() throws IOException {
        try {
            Files.delete(path);
        } catch (java.nio.file.NoSuchFileException missing) {
            // Clearing an already-empty store is idempotent.
        } catch (IOException failure) {
            throw new IOException("memory clear failed: saved memories were not removed; ensure "
                    + path + " is a removable file and retry", failure);
        }
        return "memories cleared";
    }

    private Set<String> load() throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new LinkedHashSet<>();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return new LinkedHashSet<>();
        }
        if (Files.size(path) > MAX_FILE_BYTES) return new LinkedHashSet<>();
        try {
            JsonNode parsed = JSON.readTree(Files.readAllBytes(path));
            Set<String> result = new LinkedHashSet<>();
            if (parsed == null || !parsed.isArray()) return result;
            for (JsonNode item : parsed) if (item.isTextual()) result.add(item.asText());
            return result;
        } catch (Exception invalid) {
            return new LinkedHashSet<>();
        }
    }

    private void write(Set<String> facts) throws IOException {
        Files.createDirectories(stateRoot);
        if (Files.isSymbolicLink(stateRoot) || !Files.isDirectory(stateRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("memory state root is unsafe: " + stateRoot);
        }
        ArrayNode values = JSON.createArrayNode();
        facts.forEach(values::add);
        byte[] bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(values) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("memory store exceeds the 1 MiB limit");
        Path temporary = Files.createTempFile(stateRoot, ".memories-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateShape(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("memory arguments must be an object");
        }
        arguments.fieldNames().forEachRemaining(field -> {
            if (!Set.of("action", "fact").contains(field)) {
                throw new IllegalArgumentException("unknown memory field: " + field);
            }
        });
        if (!arguments.path("action").isTextual()) {
            throw new IllegalArgumentException("memory field \"action\" must be a string");
        }
        if (arguments.has("fact") && !arguments.path("fact").isTextual()) {
            throw new IllegalArgumentException("memory field \"fact\" must be a string");
        }
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }
}
