package dev.fxjava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted one-entry-per-line prompt history bounded to the most recent
 * {@value #MAX_ENTRIES} entries, deduplicating consecutive repeats.
 */
final class PromptHistory {
    static final int MAX_ENTRIES = 500;

    private final Path file;
    private final List<String> entries;

    PromptHistory(Path sessionRoot) {
        this.file = sessionRoot.resolve("prompt_history.txt");
        this.entries = load();
    }

    List<String> entries() {
        return List.copyOf(entries);
    }

    /** Records an entry, skipping blanks and consecutive duplicates. */
    void add(String entry) {
        if (entry == null || entry.isBlank()) return;
        String normalized = KeyDecoder.normalizeNewlines(entry).strip();
        if (normalized.isEmpty()) return;
        if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(normalized)) return;
        entries.add(normalized);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder content = new StringBuilder();
        for (String entry : entries) {
            content.append(entry.replace("\n", " ")).append('\n');
        }
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<String> load() {
        List<String> loaded = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) return loaded;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (!loaded.isEmpty() && loaded.get(loaded.size() - 1).equals(line)) continue;
                loaded.add(line);
            }
        } catch (IOException unreadable) {
            return new ArrayList<>();
        }
        while (loaded.size() > MAX_ENTRIES) loaded.remove(0);
        return loaded;
    }
}
