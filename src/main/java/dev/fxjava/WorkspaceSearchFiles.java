package dev.fxjava;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Resolves a bounded search candidate set and rejects links outside its chosen root. */
final class WorkspaceSearchFiles {
    static final class Selection {
        private final List<Path> files;
        private final int outsideRoot;
        private final int unresolvable;
        private final boolean candidateLimitReached;
        private final boolean traversalLimitReached;

        Selection(List<Path> files, int outsideRoot, int unresolvable,
                  boolean candidateLimitReached, boolean traversalLimitReached) {
            this.files = List.copyOf(files);
            this.outsideRoot = outsideRoot;
            this.unresolvable = unresolvable;
            this.candidateLimitReached = candidateLimitReached;
            this.traversalLimitReached = traversalLimitReached;
        }

        List<Path> files() { return files; }
        int outsideRoot() { return outsideRoot; }
        int unresolvable() { return unresolvable; }
        boolean candidateLimitReached() { return candidateLimitReached; }
        boolean traversalLimitReached() { return traversalLimitReached; }
    }

    private WorkspaceSearchFiles() {
    }

    static Selection under(Path canonicalRoot, int maxCandidates, int maxVisitedEntries,
                           Predicate<Path> ignored)
            throws IOException {
        List<Path> files = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        int[] outsideRoot = {0};
        int[] unresolvable = {0};
        int[] visitedEntries = {0};
        boolean[] candidateLimitReached = {false};
        boolean[] traversalLimitReached = {false};
        SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            private void checkInterrupted() throws InterruptedIOException {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Search interrupted");
                }
            }

            private boolean visitBudgetExceeded() {
                visitedEntries[0]++;
                if (visitedEntries[0] <= maxVisitedEntries) return false;
                traversalLimitReached[0] = true;
                return true;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                checkInterrupted();
                if (visitBudgetExceeded()) return FileVisitResult.TERMINATE;
                return ignored.test(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path candidate, BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                if (visitBudgetExceeded()) return FileVisitResult.TERMINATE;
                if (ignored.test(candidate) || !Files.isRegularFile(candidate)) return FileVisitResult.CONTINUE;
                if (files.size() >= maxCandidates) {
                    candidateLimitReached[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                try {
                    Path canonical = candidate.toRealPath();
                    if (!canonical.startsWith(canonicalRoot)) {
                        outsideRoot[0]++;
                    } else if (Files.isRegularFile(canonical) && seen.add(canonical)) {
                        files.add(canonical);
                    }
                } catch (IOException | SecurityException unresolvablePath) {
                    unresolvable[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                checkInterrupted();
                if (visitBudgetExceeded()) return FileVisitResult.TERMINATE;
                if (!ignored.test(file)) unresolvable[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                checkInterrupted();
                if (failure != null && !ignored.test(directory)) unresolvable[0]++;
                return FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(canonicalRoot, visitor);
        return new Selection(files, outsideRoot[0], unresolvable[0],
                candidateLimitReached[0], traversalLimitReached[0]);
    }
}
