package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Compact fx-compatible implementations of the five foundational file tools. */
final class FxCoreFileTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_READ_SCAN_BYTES = 32L * 1024 * 1024;
    private static final long MAX_GREP_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_GREP_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_LINE_CHARS = 16 * 1024;
    private static final int MAX_READ_OUTPUT_CHARS = 50 * 1024;
    private static final int MAX_RESULT_LINE_CHARS = 2_000;
    private static final int MAX_MUTATION_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LIST_ENTRIES = 100;
    private static final int MAX_SCAN_FILES = 10_000;
    private static final int MAX_SEARCH_VISITED_ENTRIES = 100_000;
    private static final int MAX_CONTEXT_LINES = 5;

    private FxCoreFileTools() {
    }

    static List<Tool> create(WorkspaceTools.Workspace workspace) {
        return List.of(
                tool("list_files", "List one directory level without reading file contents.",
                        schema(new String[]{}, "path", string("Directory; defaults to .")), false,
                        args -> "list " + optionalText(args, "path", "."),
                        args -> listFiles(workspace, args)),
                tool("grep_files", "Search text files for a literal substring.",
                        schema(new String[]{"pattern"},
                                "pattern", string("Literal plain-text pattern"),
                                "path", string("Search root; defaults to ."),
                                "include", string("Optional glob filter"),
                                "case_insensitive", bool("Case-insensitive search"),
                                "mode", enumString("matches", "files_with_matches", "count"),
                                "head_limit", integer("Maximum returned results", 1, MAX_LIST_ENTRIES),
                                "offset", integer("Zero-based result offset", 0, Integer.MAX_VALUE),
                                "context_lines", integer("Context lines around matches", 0, MAX_CONTEXT_LINES)), false,
                        args -> "search for " + optionalText(args, "pattern", "?"),
                        args -> grepFiles(workspace, args)),
                tool("read_file", "Read bounded UTF-8 text with numbered lines.",
                        schema(new String[]{"path"},
                                "path", string("File path"),
                                "start_line", integer("First one-based line", 1, Integer.MAX_VALUE),
                                "line_count", integer("Maximum lines", 1, 2_000)), false,
                        args -> "read " + optionalText(args, "path", "?"),
                        args -> readFile(workspace, args)),
                tool("write_file", "Create or replace a UTF-8 file with complete contents.",
                        schema(new String[]{"path", "content"},
                                "path", string("File path"), "content", string("Complete contents")), true,
                        args -> "write " + optionalText(args, "path", "?"),
                        args -> writeFile(workspace, args)),
                tool("edit_file", "Replace exactly one occurrence in a UTF-8 file.",
                        schema(new String[]{"path", "old_string", "new_string"},
                                "path", string("File path"),
                                "old_string", string("Exact text occurring once"),
                                "new_string", string("Replacement text")), true,
                        args -> "edit " + optionalText(args, "path", "?"),
                        args -> editFile(workspace, args)));
    }

    private static String listFiles(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        Path directory = workspace.resolveExisting(optionalText(args, "path", "."));
        if (!Files.isDirectory(directory)) throw new IOException("Unable to open list directory: " + directory);
        List<Path> entries;
        try (Stream<Path> stream = Files.list(directory)) {
            entries = stream.filter(path -> !FxIgnoredPaths.direct(path)).sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_LIST_ENTRIES + 1L).collect(Collectors.toList());
        }
        String display = workspace.display(directory);
        StringBuilder output = new StringBuilder(display.isEmpty() ? "." : display).append(":\n");
        int shown = Math.min(entries.size(), MAX_LIST_ENTRIES);
        for (Path entry : entries.subList(0, shown)) {
            String suffix = Files.isSymbolicLink(entry) ? "@" : Files.isDirectory(entry) ? "/" : "";
            output.append("- ").append(entry.getFileName()).append(suffix).append('\n');
        }
        if (entries.isEmpty()) output.append("(empty)\n");
        else if (entries.size() > shown) {
            output.append("... and more entries (showing first ").append(MAX_LIST_ENTRIES).append(")\n");
        }
        return output.toString();
    }

    private static String readFile(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String rawPath = requiredText(args, "path");
        String requested = rawPath.trim();
        if (requested.isEmpty()) throw new IllegalArgumentException("read_file field \"path\" must not be empty");
        Path file = workspace.resolveExisting(requested);
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular file: " + workspace.display(file));
        int start = optionalInt(args, "start_line", 1, 1, Integer.MAX_VALUE);
        int count = optionalInt(args, "line_count", 400, 1, 2_000);
        long size = Files.size(file);
        long requestedEnd = (long) start + count - 1;
        StringBuilder output = new StringBuilder("<path>").append(workspace.display(file))
                .append("</path>\n<content>\n");
        long linesScanned = 0;
        List<ReadRow> outputLines = new ArrayList<>();
        int retainedChars = 0;
        boolean totalKnown = false;
        boolean moreLines = false;
        boolean responseLimited = false;
        boolean lineClipped = false;
        boolean scanLimited = false;
        long nextStartLine = -1;
        try (BoundedTextReader reader = BoundedTextReader.open(file, MAX_READ_SCAN_BYTES, MAX_LINE_CHARS)) {
            for (;;) {
                BoundedTextReader.Line line = reader.readLine();
                if (line == null) {
                    totalKnown = !reader.byteLimitReached();
                    scanLimited = reader.byteLimitReached();
                    break;
                }
                linesScanned++;
                if (linesScanned >= start && linesScanned <= requestedEnd) {
                    String text = line.text();
                    if (line.truncated()) {
                        text += "... [line clipped after " + MAX_LINE_CHARS + " characters]";
                    }
                    if (line.partial()) {
                        text += "... [byte scan limit reached within this line]";
                        scanLimited = true;
                    }
                    if (responseLimited) {
                        // Keep the returned page contiguous; resume at the first omitted line.
                    } else if (retainedChars + text.length() + 32 > MAX_READ_OUTPUT_CHARS) {
                        responseLimited = true;
                        nextStartLine = linesScanned;
                    } else {
                        outputLines.add(new ReadRow(linesScanned, text));
                        retainedChars += text.length() + 32;
                        if (line.truncated()) lineClipped = true;
                    }
                }
                if (line.partial()) scanLimited = true;

                if (size > MAX_READ_SCAN_BYTES && linesScanned >= requestedEnd) {
                    BoundedTextReader.Line next = reader.readLine();
                    if (next != null) {
                        moreLines = true;
                        if (next.partial()) scanLimited = true;
                    } else {
                        totalKnown = !reader.byteLimitReached();
                        scanLimited = reader.byteLimitReached();
                    }
                    break;
                }
            }
        }

        long displayedEnd = totalKnown ? Math.min(linesScanned, requestedEnd) : requestedEnd;
        int width = displayedEnd <= 0 ? 1 : Long.toString(displayedEnd).length();
        for (ReadRow row : outputLines) {
            output.append(String.format(Locale.ROOT, "%" + width + "d\t%s", row.number(), row.text()))
                    .append('\n');
        }
        int returned = outputLines.size();
        if (totalKnown && start > linesScanned && linesScanned > 0) {
            output.append("... [start_line ").append(start).append(" is beyond end of file; total lines ")
                    .append(linesScanned).append("]\n");
        } else if (totalKnown) {
            if (start != 1 || returned < linesScanned) {
                output.append("... [showing ").append(returned).append(" of ").append(linesScanned)
                        .append(" lines; use start_line/line_count to read more.]\n");
            }
        } else {
            output.append("... [showing ").append(returned).append(" lines from start_line ").append(start)
                    .append("; total line count not scanned. Use start_line/line_count to read more.]\n");
        }
        if (moreLines) output.append("... [more lines are available beyond the requested range.]\n");
        if (responseLimited) {
            output.append("... [response capped at ").append(MAX_READ_OUTPUT_CHARS)
                    .append(" characters; resume with start_line ").append(nextStartLine).append(".]\n");
        }
        if (lineClipped) output.append("... [one or more long lines were clipped.]\n");
        if (scanLimited) output.append("... [read scan stopped at ").append(MAX_READ_SCAN_BYTES)
                .append(" bytes; more file content may be available.]\n");
        return output.append("</content>").toString();
    }

    private static String grepFiles(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String pattern = requiredText(args, "pattern");
        if (pattern.isEmpty()) throw new IllegalArgumentException("pattern must not be empty");
        Path root = workspace.resolveExisting(optionalText(args, "path", "."));
        String include = optionalText(args, "include", "");
        boolean caseInsensitive = optionalBoolean(args, "case_insensitive", false);
        String mode = optionalText(args, "mode", "matches");
        int limit = optionalInt(args, "head_limit", MAX_LIST_ENTRIES, 1, MAX_LIST_ENTRIES);
        int offset = optionalInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int context = optionalInt(args, "context_lines", 0, 0, MAX_CONTEXT_LINES);
        PathMatcher includeMatcher = include.isEmpty() ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + include);

        WorkspaceSearchFiles.Selection selection = WorkspaceSearchFiles.under(root, MAX_SCAN_FILES,
                MAX_SEARCH_VISITED_ENTRIES,
                path -> FxIgnoredPaths.contains(root, path));
        List<Match> matches = new ArrayList<>();
        List<String> matchedFiles = new ArrayList<>();
        long totalMatches = 0;
        long matchingFiles = 0;
        long totalBytesRead = 0;
        int unreadableFiles = selection.unresolvable();
        int binaryFiles = 0;
        int partialFiles = 0;
        int longLineFiles = 0;
        boolean byteBudgetReached = false;
        String needle = caseInsensitive ? pattern.toLowerCase(Locale.ROOT) : pattern;
        long selectedEnd = (long) offset + limit;
        for (Path file : selection.files()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Search interrupted");
            long remainingBudget = MAX_GREP_TOTAL_BYTES - totalBytesRead;
            if (remainingBudget <= 0) {
                byteBudgetReached = true;
                break;
            }
            Path relative = root.equals(file) ? file.getFileName() : root.relativize(file);
            if (includeMatcher != null && !includeMatcher.matches(relative) && !includeMatcher.matches(file.getFileName())) {
                continue;
            }
            long fileBudget = Math.min(MAX_GREP_FILE_BYTES, remainingBudget);
            long fileMatches = 0;
            boolean binary = false;
            boolean filePartial = false;
            boolean fileHasLongLine = false;
            Deque<String> previous = new ArrayDeque<>();
            List<Match> fileResults = new ArrayList<>();
            List<Match> activeContext = new ArrayList<>();
            long lineNumber = 0;
            boolean finalLineTerminated = false;
            try {
                BoundedTextReader reader = BoundedTextReader.open(file, fileBudget, MAX_LINE_CHARS);
                try {
                    try (reader) {
                        for (BoundedTextReader.Line line; (line = reader.readLine()) != null; ) {
                            lineNumber++;
                            finalLineTerminated = line.terminated();
                            String raw = line.text();
                            if (line.containsNul()) {
                                binary = true;
                                break;
                            }
                            if (line.truncated()) fileHasLongLine = true;
                            String displayLine = clipSearchLine(raw, line.truncated() || line.partial());
                            for (int index = activeContext.size() - 1; index >= 0; index--) {
                                Match match = activeContext.get(index);
                                if (lineNumber <= match.line() + context) match.after().add(displayLine);
                                else activeContext.remove(index);
                            }
                            String haystack = caseInsensitive ? raw.toLowerCase(Locale.ROOT) : raw;
                            if (haystack.contains(needle)) {
                                long matchIndex = totalMatches + fileMatches;
                                if (matchIndex >= offset && matchIndex < selectedEnd) {
                                    Match match = new Match(workspace.display(file), lineNumber, displayLine,
                                            new ArrayList<>(previous), new ArrayList<>());
                                    fileResults.add(match);
                                    if (context > 0) activeContext.add(match);
                                }
                                fileMatches++;
                            }
                            if (context > 0) {
                                previous.addLast(displayLine);
                                while (previous.size() > context) previous.removeFirst();
                            }
                            if (line.partial()) {
                                filePartial = true;
                                break;
                            }
                        }
                        if (reader.byteLimitReached()) filePartial = true;
                    }
                } finally {
                    totalBytesRead += reader.bytesRead();
                }
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException | RuntimeException unreadable) {
                unreadableFiles++;
                continue;
            }
            if (binary) {
                binaryFiles++;
                continue;
            }
            if (!filePartial && finalLineTerminated && context > 0) {
                long trailingEmptyLine = lineNumber + 1;
                for (Match match : activeContext) {
                    if (trailingEmptyLine <= match.line() + context) match.after().add("");
                }
            }
            if (filePartial) partialFiles++;
            if (fileHasLongLine) longLineFiles++;
            if (fileMatches > 0) {
                matchingFiles++;
                if (mode.equals("files_with_matches") && matchingFiles - 1 >= offset
                        && matchingFiles - 1 < selectedEnd) {
                    matchedFiles.add(workspace.display(file));
                }
                matches.addAll(fileResults);
                totalMatches += fileMatches;
            }
            if (totalBytesRead >= MAX_GREP_TOTAL_BYTES) {
                byteBudgetReached = true;
                break;
            }
        }
        boolean incomplete = selection.outsideRoot() > 0 || unreadableFiles > 0 || binaryFiles > 0
                || partialFiles > 0 || longLineFiles > 0 || selection.candidateLimitReached()
                || selection.traversalLimitReached() || byteBudgetReached;
        String diagnostics = grepDiagnostics(selection, unreadableFiles, binaryFiles, partialFiles,
                longLineFiles, byteBudgetReached);
        if (mode.equals("count")) {
            return "[grep] count " + totalMatches + " matching lines in " + matchingFiles
                    + " files for " + pattern + diagnostics + "\n";
        }
        if (mode.equals("files_with_matches")) {
            return formatMatchingFiles(pattern, matchedFiles, matchingFiles, offset, limit, diagnostics, incomplete);
        }
        return formatMatches(pattern, matches, totalMatches, offset, limit, context, diagnostics, incomplete);
    }

    private static String formatMatchingFiles(String pattern, List<String> files, long totalFiles,
                                              int offset, int limit, String diagnostics, boolean incomplete) {
        // The caller collects only the file page selected by offset/limit while scanning.
        int start = 0;
        int end = Math.min(limit, files.size());
        if (totalFiles == 0) return "[grep] no files with matches for " + pattern + diagnostics + "\n";
        if (start == end) return "[grep] no files with matches for " + pattern + " at offset " + offset
                + " (" + totalFiles + " total files)" + diagnostics + "\n";
        String header = !incomplete && start == 0 && end == totalFiles
                ? "[grep] " + (end - start) + " files with matches for " + pattern + "\n"
                : "[grep] " + (end - start) + " files with matches for " + pattern + " (showing "
                + (offset + 1) + "-" + (offset + end - start) + " of " + totalFiles + ")"
                + diagnostics + "\n";
        StringBuilder output = new StringBuilder(header);
        files.subList(start, end).forEach(file -> output.append(" - ").append(file).append('\n'));
        if (offset + (end - start) < totalFiles) {
            output.append("... more files available; use offset ").append(offset + (end - start)).append(" to continue\n");
        }
        return output.toString();
    }

    private static String formatMatches(String pattern, List<Match> matches, long totalMatches,
                                        int offset, int limit, int context, String diagnostics,
                                        boolean incomplete) {
        int start = 0;
        int end = matches.size();
        if (totalMatches == 0) return "[grep] no matches for " + pattern + diagnostics + "\n";
        if (matches.isEmpty()) return "[grep] no matches for " + pattern + " at offset " + offset
                + " (" + totalMatches + " total matches)" + diagnostics + "\n";
        String header = !incomplete && offset == 0 && end == totalMatches
                ? "[grep] " + (end - start) + " matches for " + pattern + "\n"
                : "[grep] " + (end - start) + " matches for " + pattern + " (showing "
                + (offset + 1) + "-" + (offset + end) + " of " + totalMatches + ")"
                + diagnostics + "\n";
        StringBuilder output = new StringBuilder(header);
        for (Match match : matches.subList(start, end)) {
            long first = match.line() - match.before().size();
            for (int index = 0; index < match.before().size(); index++) {
                output.append("   ").append(match.path()).append(':').append(first + index).append("- ")
                        .append(match.before().get(index)).append('\n');
            }
            output.append(" - ").append(match.path()).append(':').append(match.line()).append(": ")
                    .append(match.text()).append('\n');
            for (int index = 0; index < match.after().size(); index++) {
                output.append("   ").append(match.path()).append(':').append(match.line() + index + 1).append("- ")
                        .append(match.after().get(index)).append('\n');
            }
        }
        if (offset + end < totalMatches) {
            output.append("... more matches available; use offset ").append(offset + end).append(" to continue\n");
        }
        return output.toString();
    }

    private static String grepDiagnostics(WorkspaceSearchFiles.Selection selection, int unreadable,
                                         int binary, int partial, int longLines, boolean byteBudgetReached) {
        List<String> details = new ArrayList<>();
        if (selection.outsideRoot() > 0) details.add(selection.outsideRoot() + " candidate(s) outside the selected search root skipped");
        if (unreadable > 0) {
            details.add(unreadable + " unreadable or unresolved file(s) skipped");
        }
        if (binary > 0) details.add(binary + " binary file(s) skipped");
        if (partial > 0) details.add(partial + " file(s) scanned only through a byte limit");
        if (longLines > 0) details.add(longLines + " file(s) contained lines longer than " + MAX_LINE_CHARS
                + " characters; omitted line suffixes may contain matches");
        if (selection.candidateLimitReached()) details.add("candidate scan stopped at " + MAX_SCAN_FILES + " files");
        if (selection.traversalLimitReached()) details.add("directory traversal stopped at "
                + MAX_SEARCH_VISITED_ENTRIES + " entries");
        if (byteBudgetReached) details.add("total text scan stopped at " + MAX_GREP_TOTAL_BYTES + " bytes");
        return details.isEmpty() ? "" : " [search incomplete: " + String.join("; ", details) + "]";
    }

    private static String clipSearchLine(String line, boolean sourceTruncated) {
        if (line.length() <= MAX_RESULT_LINE_CHARS && !sourceTruncated) return line;
        int end = Math.min(line.length(), MAX_RESULT_LINE_CHARS);
        if (end > 0 && end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))) end--;
        return line.substring(0, end) + "... [line clipped]";
    }

    private static final class ReadRow {
        private final long number;
        private final String text;

        ReadRow(long number, String text) {
            this.number = number;
            this.text = text;
        }

        long number() { return number; }
        String text() { return text; }
    }

    private static String writeFile(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String requested = requiredText(args, "path");
        String content = requiredText(args, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MUTATION_BYTES) throw new IOException("Content exceeds the 4 MiB preparation limit");
        Path target = workspace.resolveForWrite(requested);
        atomicWrite(target, content);
        return "wrote " + workspace.display(target);
    }

    private static String editFile(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String requested = requiredText(args, "path");
        String oldText = requiredText(args, "old_string");
        String newText = requiredText(args, "new_string");
        if (oldText.isEmpty()) throw new IllegalArgumentException("old_string must not be empty");
        Path target = workspace.resolveExisting(requested);
        String content = Files.readString(target, StandardCharsets.UTF_8);
        int first = content.indexOf(oldText);
        if (first < 0) throw new IOException("old_string was not found");
        if (content.indexOf(oldText, first + oldText.length()) >= 0) {
            throw new IOException("old_string occurs more than once; provide a larger unique selection");
        }
        String changed = content.substring(0, first) + newText + content.substring(first + oldText.length());
        if (changed.getBytes(StandardCharsets.UTF_8).length > MAX_MUTATION_BYTES) {
            throw new IOException("Edited content exceeds the 4 MiB preparation limit");
        }
        atomicWrite(target, changed);
        return "edited " + workspace.display(target);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".java-agent-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Tool tool(String name, String description, ObjectNode parameters, boolean approval,
                             Function<JsonNode, String> preview, Executor executor) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public ObjectNode parameters() { return parameters; }
            @Override public boolean requiresApproval() { return approval; }
            @Override public String preview(JsonNode arguments) { return preview.apply(arguments); }
            @Override public String execute(JsonNode arguments) throws Exception { return executor.run(arguments); }
        };
    }

    private static ObjectNode schema(String[] required, Object... fields) {
        ObjectNode result = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = result.putObject("properties");
        for (int index = 0; index < fields.length; index += 2) {
            properties.set((String) fields[index], (JsonNode) fields[index + 1]);
        }
        if (required.length > 0) {
            var values = result.putArray("required");
            for (String field : required) values.add(field);
        }
        result.put("additionalProperties", false);
        return result;
    }

    private static ObjectNode string(String description) {
        return JSON.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode bool(String description) {
        return JSON.createObjectNode().put("type", "boolean").put("description", description);
    }

    private static ObjectNode integer(String description, int minimum, int maximum) {
        return JSON.createObjectNode().put("type", "integer").put("description", description)
                .put("minimum", minimum).put("maximum", maximum);
    }

    private static ObjectNode enumString(String... values) {
        ObjectNode field = JSON.createObjectNode().put("type", "string");
        var choices = field.putArray("enum");
        for (String value : values) choices.add(value);
        return field;
    }

    private static String requiredText(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }

    private static String optionalText(JsonNode args, String field, String fallback) {
        JsonNode value = args.get(field);
        return value == null || value.isNull() || !value.isTextual() ? fallback : value.asText();
    }

    private static boolean optionalBoolean(JsonNode args, String field, boolean fallback) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
        return value.asBoolean();
    }

    private static int optionalInt(JsonNode args, String field, int fallback, int minimum, int maximum) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        int number = value.asInt();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return number;
    }

    private static final class Match {
        private final String path;
        private final long line;
        private final String text;
        private final List<String> before;
        private final List<String> after;

        Match(String path, long line, String text, List<String> before, List<String> after) {
            this.path = path;
            this.line = line;
            this.text = text;
            this.before = before;
            this.after = after;
        }

        public String path() { return path; }
        public long line() { return line; }
        public String text() { return text; }
        public List<String> before() { return before; }
        public List<String> after() { return after; }
    }

    @FunctionalInterface
    private interface Executor {
        String run(JsonNode arguments) throws Exception;
    }
}
