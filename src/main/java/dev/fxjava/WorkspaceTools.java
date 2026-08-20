package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Compact catalog and shared Windows-oriented workspace boundary. */
public final class WorkspaceTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 200_000;

    private WorkspaceTools() {
    }

    public static List<Tool> create(Path workspace) throws IOException {
        return create(workspace, SessionStore.defaultRoot());
    }

    public static List<Tool> create(Path workspace, Path stateRoot) throws IOException {
        Workspace paths = new Workspace(workspace);
        List<Tool> tools = new ArrayList<>(FxCoreFileTools.create(paths));
        tools.addAll(FxFileTools.create(paths));
        tools.add(SkillTool.create(workspace, stateRoot));
        tools.add(new MemoryTool(stateRoot));
        RunCommand commands = new RunCommand(paths);
        tools.add(commands);
        tools.add(new TerminalTool(paths, commands));
        return List.copyOf(tools.stream().map(tool -> (Tool) new ExternalApprovalTool(tool, paths))
                .collect(Collectors.toList()));
    }

    private static final class ExternalApprovalTool implements Tool {
        private final Tool delegate;
        private final Workspace workspace;

        ExternalApprovalTool(Tool delegate, Workspace workspace) {
            this.delegate = delegate;
            this.workspace = workspace;
        }

        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public ObjectNode parameters() { return delegate.parameters(); }
        @Override public boolean requiresApproval() { return delegate.requiresApproval(); }

        @Override
        public boolean requiresApproval(JsonNode arguments) throws Exception {
            if (delegate.requiresApproval(arguments)) return true;
            String requested;
            switch (name()) {
                case "list_files":
                case "glob_files":
                case "grep_files":
                case "semantic_search":
                    requested = optionalText(arguments, "path", ".");
                    break;
                case "read_file":
                case "file_info":
                    requested = requiredText(arguments, "path");
                    break;
                default:
                    requested = null;
                    break;
            }
            return requested != null && workspace.isExternalExisting(requested);
        }

        @Override
        public boolean autoApprove(JsonNode arguments) throws Exception {
            if (delegate.autoApprove(arguments)) return true;
            if (delegate.requiresApproval(arguments)) return false;
            return requiresApproval(arguments);
        }

        @Override public String preview(JsonNode arguments) { return delegate.preview(arguments); }
        @Override public boolean isErrorResult(String result) { return delegate.isErrorResult(result); }
        @Override public String execute(JsonNode arguments) throws Exception { return delegate.execute(arguments); }
    }

    private static final class RunCommand implements Tool {
        private final Workspace workspace;
        private final ObjectNode parameters;

        RunCommand(Workspace workspace) {
            this.workspace = workspace;
            this.parameters = objectSchema(new String[]{"command"},
                    "command", stringProperty("Shell command to run"),
                    "working_directory", stringProperty("Workspace-relative directory; defaults to ."),
                    "timeout_seconds", integerProperty("Timeout; defaults to 120", 1, 600));
        }

        @Override public String name() { return "run_command"; }
        @Override public String description() {
            return "Run one captured command in the workspace, using cmd.exe on Windows.";
        }
        @Override public ObjectNode parameters() { return parameters; }
        @Override public boolean requiresApproval() { return true; }
        @Override public String preview(JsonNode args) {
            return "run `" + abbreviate(optionalText(args, "command", "?"), 160) + "`";
        }

        @Override
        public String execute(JsonNode args) throws Exception {
            String command = requiredText(args, "command");
            int timeout = optionalInt(args, "timeout_seconds", 120, 1, 600);
            Path cwd = workspace.resolveExisting(optionalText(args, "working_directory", "."));
            if (!Files.isDirectory(cwd)) throw new IOException("Not a directory: " + workspace.display(cwd));

            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
            ProcessBuilder builder = windows
                    ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                    : new ProcessBuilder("/bin/sh", "-lc", command);
            builder.environment().remove("JAVA_AGENT_API_KEY");
            builder.environment().remove("OPENAI_API_KEY");
            Process process = builder.directory(cwd.toFile()).redirectErrorStream(true).start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> readProcessOutput(process.getInputStream()));
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
            return lexical(value).toRealPath();
        }

        Path resolveForWrite(String value) throws IOException {
            Path candidate = lexical(value);
            Path ancestor = candidate;
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) throw new IOException("Could not resolve a parent directory: " + value);
            ancestor.toRealPath();
            return candidate;
        }

        Path resolveInsideExisting(String value) throws IOException {
            Path resolved = resolveExisting(value);
            if (!resolved.startsWith(root)) throw new IOException("Path escapes workspace: " + value);
            return resolved;
        }

        Path resolveInsideCandidate(String value) throws IOException {
            Path candidate = resolveForWrite(value);
            if (!candidate.startsWith(root)) throw new IOException("Path escapes workspace: " + value);
            Path ancestor = candidate;
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null || !ancestor.toRealPath().startsWith(root)) {
                throw new IOException("Path escapes workspace: " + value);
            }
            return candidate;
        }

        boolean isExternalExisting(String value) throws IOException {
            return !resolveExisting(value).startsWith(root);
        }

        String display(Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) return normalized.toString();
            String relative = root.relativize(normalized).toString();
            return relative.isEmpty() ? "." : relative;
        }

        private Path lexical(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("path must not be blank");
            String expanded = expandHome(value);
            Path supplied = Path.of(expanded);
            return (supplied.isAbsolute() ? supplied : root.resolve(supplied)).toAbsolutePath().normalize();
        }

        private static String expandHome(String value) {
            if (!value.equals("~") && !value.startsWith("~/") && !value.startsWith("~\\")) return value;
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) throw new IllegalArgumentException("Home directory is not set");
            return value.length() == 1 ? home : Path.of(home).resolve(value.substring(2)).toString();
        }
    }

    private static ObjectNode objectSchema(String[] required, Object... fields) {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (int index = 0; index < fields.length; index += 2) {
            properties.set((String) fields[index], (JsonNode) fields[index + 1]);
        }
        if (required.length > 0) {
            var requiredNode = schema.putArray("required");
            for (String field : required) requiredNode.add(field);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode stringProperty(String description) {
        return JSON.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode enumProperty(String... values) {
        ObjectNode property = JSON.createObjectNode().put("type", "string");
        var allowed = property.putArray("enum");
        for (String value : values) allowed.add(value);
        return property;
    }

    private static ObjectNode integerProperty(String description, int minimum, int maximum) {
        return JSON.createObjectNode().put("type", "integer").put("description", description)
                .put("minimum", minimum).put("maximum", maximum);
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
