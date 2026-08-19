package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

public final class WorkspaceTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TEXT_BYTES = 1_000_000;
    private static final int MAX_OUTPUT_BYTES = 200_000;

    private WorkspaceTools() {
    }

    public static List<Tool> create(Path workspace) throws IOException {
        Workspace paths = new Workspace(workspace);
        return List.of(
                new ListFiles(paths),
                new ReadFile(paths),
                new GrepFiles(paths),
                new WriteFile(paths),
                new EditFile(paths),
                new RunCommand(paths));
    }

    private abstract static class BaseTool implements Tool {
        private final String name;
        private final String description;
        private final ObjectNode parameters;
        private final boolean requiresApproval;

        BaseTool(String name, String description, ObjectNode parameters, boolean requiresApproval) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.requiresApproval = requiresApproval;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public ObjectNode parameters() { return parameters; }
        @Override public boolean requiresApproval() { return requiresApproval; }
    }

    private static final class ListFiles extends BaseTool {
        private final Workspace workspace;

        ListFiles(Workspace workspace) {
            super("list_files", "List one directory level in the workspace. Use this to inspect a known directory.",
                    objectSchema(new String[]{},
                            "path", stringProperty("Workspace-relative directory; defaults to .")), false);
            this.workspace = workspace;
        }

        @Override public String preview(JsonNode args) { return "list " + optionalText(args, "path", "."); }

        @Override
        public String execute(JsonNode args) throws IOException {
            Path directory = workspace.resolveExisting(optionalText(args, "path", "."));
            if (!Files.isDirectory(directory)) throw new IOException("Not a directory: " + workspace.display(directory));
            List<Path> entries;
            try (Stream<Path> stream = Files.list(directory)) {
                entries = stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(501).toList();
            }
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(entries.size(), 500); i++) {
                Path entry = entries.get(i);
                result.append(Files.isDirectory(entry) ? "d " : "f ")
                        .append(workspace.display(entry)).append('\n');
            }
            if (entries.size() > 500) result.append("... output limited to 500 entries\n");
            return result.isEmpty() ? "(empty directory)" : result.toString();
        }
    }

    private static final class ReadFile extends BaseTool {
        private final Workspace workspace;

        ReadFile(Workspace workspace) {
            super("read_file", "Read a UTF-8 text file with line numbers. Output is limited to 500 lines.",
                    objectSchema(new String[]{"path"},
                            "path", stringProperty("Workspace-relative file path"),
                            "start_line", integerProperty("First one-based line; defaults to 1", 1, 10_000_000),
                            "line_count", integerProperty("Number of lines; defaults to 500", 1, 500)), false);
            this.workspace = workspace;
        }

        @Override public String preview(JsonNode args) { return "read " + optionalText(args, "path", "?"); }

        @Override
        public String execute(JsonNode args) throws IOException {
            Path file = workspace.resolveExisting(requiredText(args, "path"));
            if (!Files.isRegularFile(file)) throw new IOException("Not a regular file: " + workspace.display(file));
            int start = optionalInt(args, "start_line", 1, 1, 10_000_000);
            int count = optionalInt(args, "line_count", 500, 1, 500);
            String text = readUtf8(file);
            String[] lines = text.split("\\R", -1);
            if (start > lines.length) return "(start_line is past end of file; " + lines.length + " lines)";
            int end = Math.min(lines.length, start - 1 + count);
            StringBuilder result = new StringBuilder();
            for (int index = start - 1; index < end; index++) {
                result.append(String.format(Locale.ROOT, "%6d | %s%n", index + 1, lines[index]));
            }
            if (end < lines.length) result.append("... more lines available; continue at start_line=").append(end + 1);
            return result.toString();
        }
    }

    private static final class GrepFiles extends BaseTool {
        private final Workspace workspace;

        GrepFiles(Workspace workspace) {
            super("grep_files", "Search workspace text files for a literal string and return matching lines.",
                    objectSchema(new String[]{"query"},
                            "query", stringProperty("Literal text to find"),
                            "path", stringProperty("Workspace-relative file or directory; defaults to ."),
                            "file_suffix", stringProperty("Optional suffix filter such as .java")), false);
            this.workspace = workspace;
        }

        @Override public String preview(JsonNode args) { return "search for " + optionalText(args, "query", "?"); }

        @Override
        public String execute(JsonNode args) throws IOException {
            String query = requiredText(args, "query");
            if (query.isEmpty()) throw new IllegalArgumentException("query must not be empty");
            String suffix = optionalText(args, "file_suffix", "");
            Path start = workspace.resolveExisting(optionalText(args, "path", "."));
            List<Path> candidates;
            if (Files.isRegularFile(start)) {
                candidates = List.of(start);
            } else if (Files.isDirectory(start)) {
                try (Stream<Path> stream = Files.walk(start)) {
                    candidates = stream.filter(Files::isRegularFile)
                            .filter(path -> suffix.isEmpty() || path.getFileName().toString().endsWith(suffix))
                            .sorted().limit(10_001).toList();
                }
            } else {
                throw new IOException("Not a file or directory: " + workspace.display(start));
            }

            StringBuilder result = new StringBuilder();
            int matches = 0;
            for (Path file : candidates) {
                if (matches >= 500) break;
                if (Files.size(file) > MAX_TEXT_BYTES) continue;
                String text;
                try {
                    text = readUtf8(file);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (text.indexOf('\0') >= 0) continue;
                String[] lines = text.split("\\R", -1);
                for (int line = 0; line < lines.length && matches < 500; line++) {
                    if (lines[line].contains(query)) {
                        result.append(workspace.display(file)).append(':').append(line + 1)
                                .append(": ").append(lines[line]).append('\n');
                        matches++;
                    }
                }
            }
            if (candidates.size() > 10_000) result.append("... file scan limited to 10,000 files\n");
            if (matches >= 500) result.append("... matches limited to 500\n");
            return result.isEmpty() ? "No matches" : result.toString();
        }
    }

    private static final class WriteFile extends BaseTool {
        private final Workspace workspace;

        WriteFile(Workspace workspace) {
            super("write_file", "Create or replace a UTF-8 file in the workspace with complete contents.",
                    objectSchema(new String[]{"path", "content"},
                            "path", stringProperty("Workspace-relative file path"),
                            "content", stringProperty("Complete new file contents")), true);
            this.workspace = workspace;
        }

        @Override public String preview(JsonNode args) { return "write " + optionalText(args, "path", "?"); }

        @Override
        public String execute(JsonNode args) throws IOException {
            Path file = workspace.resolveForWrite(requiredText(args, "path"));
            String content = requiredText(args, "content");
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                throw new IOException("Content exceeds the 1 MB limit");
            }
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".java-agent-", ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + workspace.display(file);
        }
    }

    private static final class EditFile extends BaseTool {
        private final Workspace workspace;

        EditFile(Workspace workspace) {
            super("edit_file", "Replace exactly one occurrence in an existing UTF-8 workspace file.",
                    objectSchema(new String[]{"path", "old_text", "new_text"},
                            "path", stringProperty("Workspace-relative file path"),
                            "old_text", stringProperty("Exact text to replace; must occur once"),
                            "new_text", stringProperty("Replacement text")), true);
            this.workspace = workspace;
        }

        @Override public String preview(JsonNode args) { return "edit " + optionalText(args, "path", "?"); }

        @Override
        public String execute(JsonNode args) throws IOException {
            Path file = workspace.resolveExisting(requiredText(args, "path"));
            String oldText = requiredText(args, "old_text");
            String newText = requiredText(args, "new_text");
            if (oldText.isEmpty()) throw new IllegalArgumentException("old_text must not be empty");
            String content = readUtf8(file);
            int first = content.indexOf(oldText);
            if (first < 0) throw new IOException("old_text was not found");
            if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                throw new IOException("old_text occurs more than once; provide a larger unique selection");
            }
            String changed = content.substring(0, first) + newText + content.substring(first + oldText.length());
            if (changed.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                throw new IOException("Edited content exceeds the 1 MB limit");
            }
            Files.writeString(file, changed, StandardCharsets.UTF_8);
            return "Edited " + workspace.display(file);
        }
    }

    private static final class RunCommand extends BaseTool {
        private final Workspace workspace;

        RunCommand(Workspace workspace) {
            super("run_command", "Run one captured shell command in the workspace. Output is limited to 200 KB.",
                    objectSchema(new String[]{"command"},
                            "command", stringProperty("Shell command to run"),
                            "working_directory", stringProperty("Workspace-relative directory; defaults to ."),
                            "timeout_seconds", integerProperty("Timeout; defaults to 120", 1, 600)), true);
            this.workspace = workspace;
        }

        @Override public String preview(JsonNode args) { return "run `" + abbreviate(optionalText(args, "command", "?"), 160) + "`"; }

        @Override
        public String execute(JsonNode args) throws Exception {
            String command = requiredText(args, "command");
            int timeout = optionalInt(args, "timeout_seconds", 120, 1, 600);
            Path cwd = workspace.resolveExisting(optionalText(args, "working_directory", "."));
            if (!Files.isDirectory(cwd)) throw new IOException("Not a directory: " + workspace.display(cwd));

            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            ProcessBuilder builder = windows
                    ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                    : new ProcessBuilder("/bin/sh", "-lc", command);
            builder.environment().remove("JAVA_AGENT_API_KEY");
            builder.environment().remove("OPENAI_API_KEY");
            Process process = builder.directory(cwd.toFile()).redirectErrorStream(true).start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readProcessOutput(process.getInputStream()));
            boolean exited = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            String captured;
            try {
                captured = output.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException error) {
                throw new IOException("Timed out while collecting command output", error);
            }
            if (!exited) return captured + "\nCommand timed out after " + timeout + " seconds";
            return captured + (captured.endsWith("\n") || captured.isEmpty() ? "" : "\n")
                    + "Exit code: " + process.exitValue();
        }
    }

    static final class Workspace {
        private final Path root;

        Workspace(Path root) throws IOException {
            if (!Files.isDirectory(root)) throw new IOException("Workspace is not a directory: " + root);
            this.root = root.toRealPath();
        }

        Path resolveExisting(String value) throws IOException {
            Path candidate = lexical(value);
            Path real = candidate.toRealPath();
            ensureInside(real);
            return real;
        }

        Path resolveForWrite(String value) throws IOException {
            Path candidate = lexical(value);
            Path ancestor = candidate;
            while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
            if (ancestor == null) throw new IOException("Could not resolve a parent directory");
            ensureInside(ancestor.toRealPath());
            if (Files.exists(candidate)) ensureInside(candidate.toRealPath());
            return candidate;
        }

        String display(Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            return normalized.startsWith(root) ? root.relativize(normalized).toString() : normalized.toString();
        }

        private Path lexical(String value) throws IOException {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("path must not be blank");
            Path supplied = Path.of(value);
            Path candidate = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).normalize();
            ensureInside(candidate);
            return candidate;
        }

        private void ensureInside(Path path) throws IOException {
            if (!path.startsWith(root)) throw new IOException("Path is outside the workspace: " + path);
        }
    }

    private static ObjectNode objectSchema(String[] required, Object... properties) {
        if (properties.length % 2 != 0) throw new IllegalArgumentException("properties must be name/schema pairs");
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode propertyNode = schema.putObject("properties");
        for (int index = 0; index < properties.length; index += 2) {
            propertyNode.set((String) properties[index], (JsonNode) properties[index + 1]);
        }
        if (required.length > 0) {
            var requiredNode = schema.putArray("required");
            for (String field : required) requiredNode.add(field);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode stringProperty(String description) {
        ObjectNode property = JSON.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static ObjectNode integerProperty(String description, int minimum, int maximum) {
        ObjectNode property = JSON.createObjectNode();
        property.put("type", "integer");
        property.put("description", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return property;
    }

    private static String requiredText(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }

    private static String optionalText(JsonNode args, String field, String fallback) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText();
    }

    private static int optionalInt(JsonNode args, String field, int fallback, int minimum, int maximum) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        int result = value.asInt();
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private static String readUtf8(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_TEXT_BYTES) throw new IOException("File exceeds the 1 MB read limit");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String readProcessOutput(InputStream input) {
        try (input; ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            boolean truncated = false;
            for (int count; (count = input.read(buffer)) >= 0;) {
                int retain = Math.min(count, Math.max(0, MAX_OUTPUT_BYTES - total));
                if (retain > 0) kept.write(buffer, 0, retain);
                total += retain;
                if (retain < count) truncated = true;
            }
            String output = kept.toString(StandardCharsets.UTF_8);
            return truncated ? output + "\n... output truncated at 200 KB" : output;
        } catch (IOException error) {
            return "Could not read command output: " + error.getMessage();
        }
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
