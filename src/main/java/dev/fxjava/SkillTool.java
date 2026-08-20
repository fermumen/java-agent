package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Compact installed-skill discovery and bounded reader derived from fx's skill contract. */
final class SkillTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FRONTMATTER_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_BYTES = 50 * 1024;
    private final Path workspace;
    private final Path stateRoot;
    private final ObjectNode parameters;

    private SkillTool(Path workspace, Path stateRoot) {
        this.workspace = workspace;
        this.stateRoot = stateRoot;
        parameters = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("name").put("type", "string")
                .put("description", "Installed skill name from the advertised catalog");
        properties.putObject("location").put("type", "string")
                .put("description", "Exact advertised skill directory when supplied");
        properties.putObject("resource").put("type", "string")
                .put("description", "Relative text resource; defaults to SKILL.md");
        properties.putObject("offset").put("type", "integer").put("minimum", 0)
                .put("description", "UTF-8 byte offset returned by a previous chunk");
        parameters.putArray("required").add("name");
        parameters.put("additionalProperties", false);
    }

    static SkillTool create(Path workspace, Path stateRoot) throws IOException {
        return new SkillTool(workspace, stateRoot);
    }

    static String catalog(Path workspace, Path stateRoot) throws IOException {
        Map<String, Skill> found = discover(workspace, stateRoot);
        if (found.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\nAvailable skills (use the skill tool when one clearly applies):\n");
        for (Skill skill : found.values()) {
            text.append("- ").append(skill.name()).append(": ").append(skill.description())
                    .append(" [").append(skill.directory()).append("]\n");
        }
        return text.toString();
    }

    static List<Skill> inventory(Path workspace, Path stateRoot) throws IOException {
        return List.copyOf(discover(workspace, stateRoot).values());
    }

    @Override public String name() { return "skill"; }
    @Override public String description() {
        return "Read an installed skill or relative text resource in bounded chunks. Use exact advertised names and locations.";
    }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return false; }
    @Override public String preview(JsonNode arguments) {
        return "read skill " + arguments.path("name").asText("?");
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String name = requiredText(arguments, "name");
        Skill skill = discover(workspace, stateRoot).get(name);
        if (skill == null) throw new IOException("Unknown installed skill: " + name);
        JsonNode location = arguments.get("location");
        if (location != null && !location.isNull()) {
            if (!location.isTextual() || !Path.of(location.asText()).toAbsolutePath().normalize()
                    .equals(skill.directory())) throw new IOException("Skill location does not match the catalog");
        }
        String resource = arguments.path("resource").asText("SKILL.md");
        if (resource.isBlank()) resource = "SKILL.md";
        Path relative = Path.of(resource);
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
            throw new IOException("Skill resource must be a relative path inside the skill");
        }
        Path file = skill.directory().resolve(relative).normalize();
        if (!file.startsWith(skill.directory()) || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Skill resource is not a regular in-skill file: " + resource);
        }
        Path canonical = file.toRealPath();
        if (!canonical.startsWith(skill.directory())) throw new IOException("Skill resource escapes its directory");
        long size = Files.size(canonical);
        int offset = optionalOffset(arguments);
        if (offset > size) throw new IOException("Skill offset exceeds resource length");
        int wanted = (int) Math.min(MAX_CHUNK_BYTES, size - offset);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(canonical)) {
            skipFully(input, offset);
            bytes = input.readNBytes(wanted);
        }
        int end = offset + bytes.length;
        String content = new String(bytes, StandardCharsets.UTF_8);
        return content + (end < size ? "\n[next_offset=" + end + "]" : "\n[end]");
    }

    private static void skipFully(InputStream input, long byteCount) throws IOException {
        long remaining = byteCount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() < 0) {
                throw new EOFException("Unexpected end of skill resource");
            } else {
                remaining--;
            }
        }
    }

    private static Map<String, Skill> discover(Path workspace, Path stateRoot) throws IOException {
        LinkedHashMap<String, Skill> result = new LinkedHashMap<>();
        List<Path> roots = new ArrayList<>();
        Path current = workspace.toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        addWorkspaceRoots(roots, current);
        if (current.startsWith(home)) {
            while (!current.equals(home)) {
                current = current.getParent();
                addWorkspaceRoots(roots, current);
            }
        }
        roots.add(stateRoot.toAbsolutePath().normalize().resolve("skills"));
        roots.add(home.resolve(".config/opencode/skills"));
        roots.add(home.resolve(".codex/skills"));
        roots.add(home.resolve(".claude/skills"));
        roots.add(home.resolve(".agents/skills"));
        roots.add(home.resolve(".claw/skills"));
        for (Path root : roots) discoverRoot(root, result);
        return Collections.unmodifiableMap(result);
    }

    private static void addWorkspaceRoots(List<Path> roots, Path parent) {
        roots.add(parent.resolve("skills"));
        roots.add(parent.resolve(".opencode/skills"));
        roots.add(parent.resolve(".codex/skills"));
        roots.add(parent.resolve(".claude/skills"));
        roots.add(parent.resolve(".agents/skills"));
        roots.add(parent.resolve(".claw/skills"));
    }

    private static void discoverRoot(Path root, LinkedHashMap<String, Skill> result) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        try (var children = Files.list(root)) {
            for (Path child : children.sorted().collect(Collectors.toList())) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) continue;
                Path file = child.resolve("SKILL.md");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) continue;
                Skill skill = metadata(child.toRealPath(), file);
                if (skill != null) result.putIfAbsent(skill.name(), skill);
            }
        }
    }

    static Skill metadata(Path directory, Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_FRONTMATTER_BYTES + 1);
        }
        if (bytes.length > MAX_FRONTMATTER_BYTES && startsFrontmatter(bytes)) return null;
        byte[] prefix = bytes.length <= MAX_FRONTMATTER_BYTES ? bytes
                : java.util.Arrays.copyOf(bytes, MAX_FRONTMATTER_BYTES);
        SkillMetadata.Resolved resolved = SkillMetadata.parse(prefix)
                .resolve(directory.getFileName().toString());
        return resolved == null ? null : new Skill(resolved.name(), resolved.description(), directory);
    }

    private static boolean startsFrontmatter(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == '-' && bytes[1] == '-' && bytes[2] == '-';
    }

    private static String requiredText(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private static int optionalOffset(JsonNode arguments) {
        JsonNode value = arguments.get("offset");
        if (value == null || value.isNull()) return 0;
        if (!value.canConvertToInt() || value.asInt() < 0) throw new IllegalArgumentException("offset must be non-negative");
        return value.asInt();
    }

    static final class Skill {
        private final String name;
        private final String description;
        private final Path directory;

        Skill(String name, String description, Path directory) {
            this.name = name;
            this.description = description;
            this.directory = directory;
        }

        public String name() { return name; }
        public String description() { return description; }
        public Path directory() { return directory; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Skill)) return false;
            Skill that = (Skill) other;
            return Objects.equals(name, that.name)
                    && Objects.equals(description, that.description)
                    && Objects.equals(directory, that.directory);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(description);
            result = 31 * result + Objects.hashCode(directory);
            return result;
        }

        @Override
        public String toString() {
            return "Skill[name=" + name + ", description=" + description + ", directory=" + directory + "]";
        }
    }
}
