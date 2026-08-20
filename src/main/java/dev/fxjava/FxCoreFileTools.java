package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Compact fx-compatible implementations of the five foundational file tools. */
final class FxCoreFileTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_READ_BYTES = 50 * 1024;
    private static final int MAX_MUTATION_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LIST_ENTRIES = 100;
    private static final int MAX_SCAN_FILES = 10_000;
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
        if (Files.size(file) > MAX_READ_BYTES) throw new IOException("File exceeds the 50 KiB read limit");
        int start = optionalInt(args, "start_line", 1, 1, Integer.MAX_VALUE);
        int count = optionalInt(args, "line_count", 400, 1, 2_000);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String[] split = text.split("\\R", -1);
        int total = split.length;
        if (!text.isEmpty() && endsWithLineBreak(text)) total--;
        if (text.isEmpty()) total = 0;

        StringBuilder output = new StringBuilder("<path>").append(workspace.display(file))
                .append("</path>\n<content>\n");
        if (start > total && total > 0) {
            output.append("... [start_line ").append(start).append(" is beyond end of file; total lines ")
                    .append(total).append("]\n");
        } else {
            int end = Math.min(total, start - 1 + count);
            int width = end <= 0 ? 1 : Integer.toString(end).length();
            for (int index = start - 1; index < end; index++) {
                output.append(String.format(Locale.ROOT, "%" + width + "d\t%s", index + 1, split[index]))
                        .append('\n');
            }
            int shown = Math.max(0, end - (start - 1));
            if (start != 1 || shown < total) {
                output.append("... [showing ").append(shown).append(" of ").append(total)
                        .append(" lines; use start_line/line_count to read more.]\n");
            }
        }
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

        List<Path> candidates;
        try (Stream<Path> stream = Files.isRegularFile(root) ? Stream.of(root) : Files.walk(root)) {
            candidates = stream.filter(Files::isRegularFile).filter(path -> !FxIgnoredPaths.contains(root, path))
                    .limit(MAX_SCAN_FILES + 1L).collect(Collectors.toList());
        }
        List<Match> matches = new ArrayList<>();
        String needle = caseInsensitive ? pattern.toLowerCase(Locale.ROOT) : pattern;
        for (Path file : candidates.subList(0, Math.min(candidates.size(), MAX_SCAN_FILES))) {
            Path relative = Files.isRegularFile(root) ? file.getFileName() : root.relativize(file);
            if (includeMatcher != null && !includeMatcher.matches(relative) && !includeMatcher.matches(file.getFileName())) {
                continue;
            }
            if (Files.size(file) > MAX_READ_BYTES) continue;
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            if (content.indexOf('\0') >= 0) continue;
            String[] lines = content.split("\\R", -1);
            int realLines = lines.length - (endsWithLineBreak(content) ? 1 : 0);
            for (int index = 0; index < realLines; index++) {
                String haystack = caseInsensitive ? lines[index].toLowerCase(Locale.ROOT) : lines[index];
                if (haystack.contains(needle)) {
                    matches.add(new Match(workspace.display(file), index + 1, lines[index], lines));
                }
            }
        }
        if (mode.equals("count")) {
            long files = matches.stream().map(Match::path).distinct().count();
            return "[grep] count " + matches.size() + " matching lines in " + files + " files for " + pattern + "\n";
        }
        if (mode.equals("files_with_matches")) return formatMatchingFiles(pattern, matches, offset, limit);
        return formatMatches(pattern, matches, offset, limit, context);
    }

    private static String formatMatchingFiles(String pattern, List<Match> matches, int offset, int limit) {
        List<String> files = new ArrayList<>(matches.stream().map(Match::path)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        int start = Math.min(offset, files.size());
        int end = Math.min(start + limit, files.size());
        if (files.isEmpty()) return "[grep] no files with matches for " + pattern + "\n";
        if (start == end) return "[grep] no files with matches for " + pattern + " at offset " + offset
                + " (" + files.size() + " total files)\n";
        String header = start == 0 && end == files.size()
                ? "[grep] " + (end - start) + " files with matches for " + pattern + "\n"
                : "[grep] " + (end - start) + " files with matches for " + pattern + " (showing "
                + (start + 1) + "-" + end + " of " + files.size() + ")\n";
        StringBuilder output = new StringBuilder(header);
        files.subList(start, end).forEach(file -> output.append(" - ").append(file).append('\n'));
        if (end < files.size()) output.append("... more files available; use offset ").append(end).append(" to continue\n");
        return output.toString();
    }

    private static String formatMatches(String pattern, List<Match> matches, int offset, int limit, int context) {
        int start = Math.min(offset, matches.size());
        int end = Math.min(start + limit, matches.size());
        if (matches.isEmpty()) return "[grep] no matches for " + pattern + "\n";
        if (start == end) return "[grep] no matches for " + pattern + " at offset " + offset
                + " (" + matches.size() + " total matches)\n";
        String header = start == 0 && end == matches.size()
                ? "[grep] " + (end - start) + " matches for " + pattern + "\n"
                : "[grep] " + (end - start) + " matches for " + pattern + " (showing "
                + (start + 1) + "-" + end + " of " + matches.size() + ")\n";
        StringBuilder output = new StringBuilder(header);
        for (Match match : matches.subList(start, end)) {
            int first = Math.max(1, match.line() - context);
            int last = Math.min(match.lines().length, match.line() + context);
            for (int line = first; line < match.line(); line++) {
                output.append("   ").append(match.path()).append(':').append(line).append("- ")
                        .append(clip(match.lines()[line - 1])).append('\n');
            }
            output.append(" - ").append(match.path()).append(':').append(match.line()).append(": ")
                    .append(clip(match.text())).append('\n');
            for (int line = match.line() + 1; line <= last; line++) {
                output.append("   ").append(match.path()).append(':').append(line).append("- ")
                        .append(clip(match.lines()[line - 1])).append('\n');
            }
        }
        if (end < matches.size()) output.append("... more matches available; use offset ").append(end).append(" to continue\n");
        return output.toString();
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

    private static boolean endsWithLineBreak(String text) {
        return text.endsWith("\n") || text.endsWith("\r");
    }

    private static String clip(String line) {
        return line.length() <= 2_000 ? line : line.substring(0, 2_000) + "...";
    }

    private static final class Match {
        private final String path;
        private final int line;
        private final String text;
        private final String[] lines;

        Match(String path, int line, String text, String[] lines) {
            this.path = path;
            this.line = line;
            this.text = text;
            this.lines = lines;
        }

        public String path() { return path; }
        public int line() { return line; }
        public String text() { return text; }
        public String[] lines() { return lines; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Match)) return false;
            Match that = (Match) other;
            return line == that.line && Objects.equals(path, that.path)
                    && Objects.equals(text, that.text) && Objects.equals(lines, that.lines);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(path);
            result = 31 * result + Integer.hashCode(line);
            result = 31 * result + Objects.hashCode(text);
            return 31 * result + Objects.hashCode(lines);
        }

        @Override
        public String toString() {
            return "Match[path=" + path + ", line=" + line + ", text=" + text + ", lines=" + lines + "]";
        }
    }

    @FunctionalInterface
    private interface Executor {
        String run(JsonNode arguments) throws Exception;
    }
}
