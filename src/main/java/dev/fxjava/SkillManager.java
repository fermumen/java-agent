package dev.fxjava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/** Managed skill create/remove operations kept separate from compatibility roots. */
final class SkillManager {
    private final Path managedRoot;

    SkillManager(Path stateRoot) {
        managedRoot = stateRoot.toAbsolutePath().normalize().resolve("skills");
    }

    Path root() {
        return managedRoot;
    }

    Path create(String name) throws IOException {
        requireName(name);
        Path root = prepareRoot();
        Path directory = root.resolve(name);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Managed skill path is unsafe: " + directory);
            }
        } else {
            Files.createDirectory(directory);
        }
        Path file = directory.resolve("SKILL.md");
        if (Files.isSymbolicLink(file)) throw new IOException("Managed skill file is a symlink: " + file);
        String template = "---\nname: " + name
                + "\ndescription: Describe when this skill should activate\n---\n\n# " + name
                + "\n\nInstructions for this skill...\n";
        atomicWrite(file, template);
        return file;
    }

    void remove(String name) throws IOException {
        requireName(name);
        Path root = requireRoot();
        Path directory = root.resolve(name);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed skill does not exist or is unsafe: " + name);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    boolean owns(Path skillDirectory) throws IOException {
        if (!Files.exists(managedRoot, LinkOption.NOFOLLOW_LINKS)) return false;
        Path root = requireRoot();
        Path candidate = skillDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) return false;
        return candidate.toRealPath().getParent().equals(root);
    }

    private Path prepareRoot() throws IOException {
        Files.createDirectories(managedRoot);
        return requireRoot();
    }

    private Path requireRoot() throws IOException {
        if (Files.isSymbolicLink(managedRoot) || !Files.isDirectory(managedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed skills root is missing or unsafe: " + managedRoot);
        }
        Path real = managedRoot.toRealPath();
        if (!real.equals(managedRoot)) throw new IOException("Managed skills root traverses a symlink: " + managedRoot);
        return real;
    }

    private static void requireName(String name) {
        if (!SkillMetadata.validManagedName(name)) {
            throw new IllegalArgumentException("Invalid skill name; use one directory name without '/' or '\\'");
        }
    }

    private static void atomicWrite(Path target, String value) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".skill-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
