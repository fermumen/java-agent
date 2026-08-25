package dev.fxjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Persistence round-trip, bounds, and dedup for prompt history files. */
class PromptHistoryTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsEntriesThroughFile() throws IOException {
        Path root = temporary.resolve("state");
        PromptHistory history = new PromptHistory(root);
        history.add("first question");
        history.add("second question");
        history.save();

        assertTrue(Files.isRegularFile(root.resolve("prompt_history.txt")));
        assertEquals(List.of("first question", "second question"),
                new PromptHistory(root).entries());
    }

    @Test
    void skipsBlankAndConsecutiveDuplicates() throws IOException {
        PromptHistory history = new PromptHistory(temporary);
        history.add("same");
        history.add("same");
        history.add("  ");
        history.add(null);
        history.add("other");
        history.add("same");
        history.save();
        assertEquals(List.of("same", "other", "same"), history.entries());
    }

    @Test
    void loadDeduplicatesConsecutiveLines() throws IOException {
        Path file = temporary.resolve("prompt_history.txt");
        Files.write(file, ("alpha\nalpha\nbeta\n\nalpha\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("alpha", "beta", "alpha"), new PromptHistory(temporary).entries());
    }

    @Test
    void boundsToMostRecentFiveHundredOnAdd() throws IOException {
        PromptHistory history = new PromptHistory(temporary);
        for (int index = 0; index < PromptHistory.MAX_ENTRIES + 50; index++) {
            history.add("entry-" + index);
        }
        List<String> entries = history.entries();
        assertEquals(PromptHistory.MAX_ENTRIES, entries.size());
        assertEquals("entry-50", entries.get(0));
        assertEquals("entry-549", entries.get(entries.size() - 1));
        history.save();
        assertEquals(PromptHistory.MAX_ENTRIES,
                Files.readAllLines(temporary.resolve("prompt_history.txt"),
                        StandardCharsets.UTF_8).size());
    }

    @Test
    void boundsToMostRecentFiveHundredOnLoad() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < PromptHistory.MAX_ENTRIES + 10; index++) {
            content.append("old-").append(index).append('\n');
        }
        Files.write(temporary.resolve("prompt_history.txt"),
                content.toString().getBytes(StandardCharsets.UTF_8));
        List<String> entries = new PromptHistory(temporary).entries();
        assertEquals(PromptHistory.MAX_ENTRIES, entries.size());
        assertEquals("old-10", entries.get(0));
    }

    @Test
    void multiLineEntriesFlattenToSingleFileLines() throws IOException {
        PromptHistory history = new PromptHistory(temporary);
        history.add("line one\nline two");
        history.save();
        assertEquals(List.of("line one line two"),
                Files.readAllLines(temporary.resolve("prompt_history.txt"),
                        StandardCharsets.UTF_8));
    }

    @Test
    void missingFileLoadsEmpty() {
        assertEquals(List.of(), new PromptHistory(temporary.resolve("nowhere")).entries());
    }
}
