package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The compact remainder of fx's filesystem tool surface. */
final class FxFileTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_READ_BYTES = 50 * 1024;
    private static final int MAX_RESULTS = 100;
    private static final int MAX_CANDIDATES = 10_000;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "in", "on", "at", "to", "for",
            "of", "and", "or", "not", "it", "this", "that", "with", "from", "by", "as",
            "do", "does", "how", "what", "where", "when", "why", "which");
    private static final DateTimeFormatter MODIFIED_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private FxFileTools() {
    }

    static List<Tool> create(WorkspaceTools.Workspace workspace) {
        return List.of(
                tool("glob_files", "Find workspace files matching a glob pattern.",
                        schema(new String[]{"pattern"},
                                "pattern", string("Glob such as src/**/*.java"),
                                "path", string("Search root; defaults to ."),
                                "mode", enumString("matches", "count")), false,
                        args -> "glob " + optionalText(args, "pattern", "?"),
                        args -> glob(workspace, args)),
                tool("delete_file", "Delete one file or empty directory.",
                        schema(new String[]{"path"}, "path", string("Workspace-relative path")), true,
                        args -> "delete " + optionalText(args, "path", "?"),
                        args -> delete(workspace, args)),
                tool("rename_file", "Rename or move a file, creating destination parents.",
                        schema(new String[]{"old_path", "new_path"},
                                "old_path", string("Current workspace-relative path"),
                                "new_path", string("New workspace-relative path")), true,
                        args -> "rename " + optionalText(args, "old_path", "?"),
                        args -> rename(workspace, args)),
                tool("copy_file", "Copy one regular file, creating destination parents.",
                        schema(new String[]{"source", "destination"},
                                "source", string("Source workspace-relative path"),
                                "destination", string("Destination workspace-relative path")), true,
                        args -> "copy " + optionalText(args, "source", "?"),
                        args -> copy(workspace, args)),
                tool("create_folder", "Create a directory and any missing parents.",
                        schema(new String[]{"path"}, "path", string("Workspace-relative directory")), true,
                        args -> "create folder " + optionalText(args, "path", "?"),
                        args -> createFolder(workspace, args)),
                tool("file_info", "Report file type, size, modification time, and extension.",
                        schema(new String[]{"path"}, "path", string("Workspace-relative path")), false,
                        args -> "inspect " + optionalText(args, "path", "?"),
                        args -> fileInfo(workspace, args)),
                tool("semantic_search", "Rank files using lexical concept keywords.",
                        schema(new String[]{"query"},
                                "query", string("Natural-language concept query"),
                                "path", string("Search root; defaults to .")), false,
                        args -> "search concepts " + optionalText(args, "query", "?"),
                        args -> semanticSearch(workspace, args)),
                tool("open_file", "Open a file with the Windows default application.",
                        schema(new String[]{"path"}, "path", string("Workspace-relative path")), true,
                        args -> "open " + optionalText(args, "path", "?"),
                        args -> openFile(workspace, args)));
    }

    private static String glob(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String pattern = requiredText(args, "pattern");
        String mode = optionalText(args, "mode", "matches");
        Path root = workspace.resolveExisting(optionalText(args, "path", "."));
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException invalidPattern) {
            throw new IllegalArgumentException("Invalid glob pattern: " + pattern, invalidPattern);
        }

        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.isRegularFile(root) ? Stream.of(root) : Files.walk(root)) {
            stream.filter(Files::isRegularFile).filter(path -> !FxIgnoredPaths.contains(root, path)).limit(MAX_CANDIDATES + 1L).forEach(path -> {
                Path relative = Files.isRegularFile(root) ? path.getFileName() : root.relativize(path);
                if (matcher.matches(relative)) matches.add(workspace.display(path));
            });
        }
        matches.sort(String::compareTo);
        if (mode.equals("count")) return "[glob] count " + matches.size() + " matches for " + pattern + "\n";
        if (matches.isEmpty()) return "[glob] no matches for " + pattern + "\n";

        int shown = Math.min(matches.size(), MAX_RESULTS);
        StringBuilder output = new StringBuilder("[glob] ").append(shown).append(" matches for ")
                .append(pattern).append('\n');
        matches.subList(0, shown).forEach(path -> output.append(" - ").append(path).append('\n'));
        if (shown < matches.size()) output.append("... truncated to first ").append(MAX_RESULTS).append(" matches\n");
        return output.toString();
    }

    private static String delete(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        Path target = workspace.resolveExisting(requiredText(args, "path"));
        String display = workspace.display(target);
        try {
            Files.delete(target);
        } catch (DirectoryNotEmptyException notEmpty) {
            throw new IOException("delete_file failed: directory not empty: " + display, notEmpty);
        }
        return "deleted " + display;
    }

    private static String rename(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String oldPath = requiredText(args, "old_path");
        String newPath = requiredText(args, "new_path");
        Path source = workspace.resolveExisting(oldPath);
        Path destination = workspace.resolveForWrite(newPath);
        String from = workspace.display(source);
        String to = workspace.display(destination);
        if (!samePath(source, destination)) {
            createParents(destination);
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return "renamed " + from + " -> " + to;
    }

    private static String copy(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String sourcePath = requiredText(args, "source");
        String destinationPath = requiredText(args, "destination");
        Path source = workspace.resolveExisting(sourcePath);
        if (!Files.isRegularFile(source)) throw new IOException("copy_file failed: " + workspace.display(source));
        Path destination = workspace.resolveForWrite(destinationPath);
        if (Files.exists(destination) && !Files.isRegularFile(destination)) {
            throw new IOException("copy_file failed: " + workspace.display(source));
        }
        String from = workspace.display(source);
        String to = workspace.display(destination);
        if (!samePath(source, destination)) {
            createParents(destination);
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
        return "copied " + from + " -> " + to;
    }

    private static String createFolder(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        Path target = workspace.resolveForWrite(requiredText(args, "path"));
        String display = workspace.display(target);
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                throw new IOException("create_folder failed: target exists and is not a directory: " + display);
            }
            return "directory already exists: " + display;
        }
        Files.createDirectories(target);
        return "created " + display;
    }

    private static String fileInfo(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String requested = requiredText(args, "path").trim();
        if (requested.isEmpty()) throw new IllegalArgumentException("file_info field \"path\" must not be empty");
        Path target = workspace.resolveExisting(requested);
        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
        String type = attributes.isDirectory() ? "directory"
                : attributes.isSymbolicLink() ? "symlink" : attributes.isRegularFile() ? "file" : "other";
        StringBuilder output = new StringBuilder()
                .append("path: ").append(workspace.display(target)).append('\n')
                .append("type: ").append(type).append('\n')
                .append("size: ").append(attributes.size()).append(" bytes\n")
                .append("modified: ").append(MODIFIED_TIME.format(attributes.lastModifiedTime().toInstant())).append('\n');
        if (attributes.isRegularFile()) {
            String name = workspace.display(target);
            int dot = name.lastIndexOf('.');
            if (dot >= 0) output.append("extension: ").append(name.substring(dot + 1)).append('\n');
        }
        return output.toString();
    }

    private static String semanticSearch(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        String query = requiredText(args, "query");
        List<String> keywords = Stream.of(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+"))
                .filter(word -> word.length() > 1).filter(word -> !STOP_WORDS.contains(word)).distinct().limit(16)
                .collect(Collectors.toList());
        if (keywords.isEmpty()) return "[search] empty query\n";
        Path root = workspace.resolveExisting(optionalText(args, "path", "."));
        List<SearchHit> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.isRegularFile(root) ? Stream.of(root) : Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> !FxIgnoredPaths.contains(root, path)).limit(2_000)
                    .collect(Collectors.toList())) {
                if (Files.size(file) > MAX_READ_BYTES * 2L) continue;
                String content;
                try {
                    content = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                String lowerName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                String[] lines = content.split("\\R", -1);
                int score = 0;
                int sampleLine = 1;
                String sample = "";
                for (String keyword : keywords) {
                    if (lowerName.contains(keyword)) score += 3;
                    for (int index = 0; index < lines.length; index++) {
                        if (lines[index].toLowerCase(Locale.ROOT).contains(keyword)) {
                            score++;
                            if (sample.isEmpty()) {
                                sampleLine = index + 1;
                                sample = lines[index];
                            }
                        }
                    }
                }
                if (score > 0) hits.add(new SearchHit(workspace.display(file), score, sampleLine, sample));
            }
        }
        hits.sort(Comparator.comparingInt(SearchHit::score).reversed().thenComparing(SearchHit::path));
        if (hits.isEmpty()) return "[search] no results for: " + query + "\n";
        int shown = Math.min(hits.size(), MAX_RESULTS);
        StringBuilder output = new StringBuilder("[search] ").append(shown).append(" results for: ")
                .append(query).append('\n');
        hits.subList(0, shown).forEach(hit -> output.append(hit.path()).append(':')
                .append(hit.line()).append(": ").append(clip(hit.sample(), 2_000)).append('\n'));
        if (shown < hits.size()) output.append("... and ").append(hits.size() - shown).append(" more\n");
        return output.toString();
    }

    private static String openFile(WorkspaceTools.Workspace workspace, JsonNode args) throws IOException {
        Path target = workspace.resolveExisting(requiredText(args, "path"));
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new IOException("open_file is supported only on Windows");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("open_file is unavailable in this Windows session");
        }
        Desktop.getDesktop().open(target.toFile());
        return "opened " + workspace.display(target);
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
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (int index = 0; index < fields.length; index += 2) {
            properties.set((String) fields[index], (JsonNode) fields[index + 1]);
        }
        if (required.length > 0) {
            var values = schema.putArray("required");
            for (String field : required) values.add(field);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode string(String description) {
        return JSON.createObjectNode().put("type", "string").put("description", description);
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
        if (value == null || value.isNull() || !value.isTextual()) return fallback;
        return value.asText();
    }

    private static void createParents(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private static boolean samePath(Path first, Path second) throws IOException {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
                || (Files.exists(second) && Files.isSameFile(first, second));
    }

    private static String clip(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static final class SearchHit {
        private final String path;
        private final int score;
        private final int line;
        private final String sample;

        SearchHit(String path, int score, int line, String sample) {
            this.path = path;
            this.score = score;
            this.line = line;
            this.sample = sample;
        }

        public String path() { return path; }
        public int score() { return score; }
        public int line() { return line; }
        public String sample() { return sample; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SearchHit)) return false;
            SearchHit that = (SearchHit) other;
            return score == that.score && line == that.line && Objects.equals(path, that.path)
                    && Objects.equals(sample, that.sample);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(path);
            result = 31 * result + Integer.hashCode(score);
            result = 31 * result + Integer.hashCode(line);
            return 31 * result + Objects.hashCode(sample);
        }

        @Override
        public String toString() {
            return "SearchHit[path=" + path + ", score=" + score + ", line=" + line
                    + ", sample=" + sample + "]";
        }
    }

    @FunctionalInterface
    private interface Executor {
        String run(JsonNode arguments) throws Exception;
    }
}
