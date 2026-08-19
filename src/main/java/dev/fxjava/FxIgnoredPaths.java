package dev.fxjava;

import java.nio.file.Path;
import java.util.Set;

/** Shared default ignored names from fx's tool dispatch contract. */
final class FxIgnoredPaths {
    private static final Set<String> NAMES = Set.of(
            ".git", ".zig-cache", "zig-out", "node_modules", ".next",
            "dist", "build", "coverage");

    private FxIgnoredPaths() {
    }

    static boolean direct(Path path) {
        Path name = path.getFileName();
        return name != null && NAMES.contains(name.toString());
    }

    static boolean contains(Path root, Path path) {
        Path relative = root.equals(path) ? Path.of("") : root.relativize(path);
        for (Path component : relative) {
            if (NAMES.contains(component.toString())) return true;
        }
        return false;
    }
}
