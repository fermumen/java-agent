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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Atomic bounded installer for local FX-compatible skill directories. */
final class InstallSkillTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FILES = 1024;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final Path workspace;
    private final Path managedRoot;
    private final ObjectNode parameters;

    InstallSkillTool(Path workspace, Path stateRoot) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.managedRoot = stateRoot.toAbsolutePath().normalize().resolve("skills");
        parameters = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("source").put("type", "string")
                .put("description", "Local skill directory or local repository containing skills.");
        properties.putObject("skill").put("type", "string")
                .put("description", "Optional exact skill name for a multi-skill source.");
        parameters.putArray("required").add("source");
        parameters.put("additionalProperties", false);
    }

    @Override public String name() { return "install_skill"; }
    @Override public String description() {
        return "Install one FX-compatible skill from a local path into managed skill storage.";
    }
    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return true; }
    @Override public String preview(JsonNode arguments) {
        return "install skill from " + arguments.path("source").asText("?");
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        if (!arguments.isObject() || !arguments.path("source").isTextual()
                || arguments.path("source").asText().isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        String filter = arguments.path("skill").isTextual() ? arguments.path("skill").asText().trim() : "";
        Path supplied = Path.of(arguments.path("source").asText());
        Path source = (supplied.isAbsolute() ? supplied : workspace.resolve(supplied)).normalize();
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Local skill source must be a non-symlink directory: " + source);
        }
        source = source.toRealPath();
        List<SkillTool.Skill> found = discover(source);
        if (!filter.isBlank()) {
            found = found.stream().filter(skill -> skill.name().equals(filter)).collect(Collectors.toList());
        }
        if (found.isEmpty()) throw new IOException("No matching skills found in local source: " + source);
        if (found.size() != 1) throw new IOException("Multiple skills found; provide the exact skill field");
        SkillTool.Skill skill = found.get(0);

        prepareManagedRoot();
        Path target = managedRoot.resolve(skill.name()).normalize();
        if (!target.getParent().equals(managedRoot) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed skill already exists or has an unsafe name: " + skill.name());
        }
        Path temporary = managedRoot.resolve(".install-" + UUID.randomUUID()).normalize();
        try {
            copyTree(skill.directory(), temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (Exception failure) {
            deleteTree(temporary);
            throw failure;
        }
        return "Installed skill " + skill.name() + " at " + target;
    }

    private static List<SkillTool.Skill> discover(Path source) throws IOException {
        List<SkillTool.Skill> found = new ArrayList<>();
        try (var paths = Files.walk(source, 5)) {
            for (Path file : paths.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .sorted().collect(Collectors.toList())) {
                if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                SkillTool.Skill skill = SkillTool.metadata(file.getParent().toRealPath(), file);
                if (skill != null) found.add(skill);
            }
        }
        return found;
    }

    private void prepareManagedRoot() throws IOException {
        Path ancestor = managedRoot;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null || Files.isSymbolicLink(ancestor)) {
            throw new IOException("Managed skill state root must not be a symlink");
        }
        ancestor.toRealPath();
        Files.createDirectories(managedRoot);
        if (Files.isSymbolicLink(managedRoot) || !Files.isDirectory(managedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed skills path is unsafe");
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        int files = 0;
        long bytes = 0;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().collect(Collectors.toList())) {
                if (Files.isSymbolicLink(path)) throw new IOException("Skills containing symlinks cannot be installed");
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) throw new IOException("Skill path escapes installation root");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files++;
                    bytes += Files.size(path);
                    if (files > MAX_FILES || bytes > MAX_BYTES) throw new IOException("Skill exceeds installation limits");
                    Files.copy(path, destination);
                } else {
                    throw new IOException("Skill contains an unsupported file type");
                }
            }
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) { }
    }
}
