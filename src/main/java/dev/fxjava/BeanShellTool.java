package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Runs a BeanShell script through the productivity bundle's fail-closed runner
 * in a child JVM, so scripting works on a plain JRE without jshell or javac.
 */
final class BeanShellTool implements Tool {
    static final String NAME = "beanshell";
    static final int MAX_INLINE_SCRIPT_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkspaceTools.Workspace workspace;
    private final Path java;
    private final Supplier<Path> productivityJar;
    private final ObjectNode parameters;

    BeanShellTool(WorkspaceTools.Workspace workspace, Path java, Supplier<Path> productivityJar) {
        this.workspace = workspace;
        this.java = java;
        this.productivityJar = productivityJar;
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("script").put("type", "string")
                .put("description", "Inline BeanShell source. Use script or path, not both; leave the unused one empty.");
        properties.putObject("path").put("type", "string")
                .put("description", "Workspace-relative .bsh file to run. Use script or path, not both; leave the unused one empty.");
        ObjectNode args = properties.putObject("args").put("type", "array")
                .put("description", "Script arguments, available as the String[] bsh.args");
        args.putObject("items").put("type", "string");
        properties.putObject("working_directory").put("type", "string")
                .put("description", "Workspace-relative directory; defaults to .");
        properties.putObject("timeout_seconds").put("type", "integer")
                .put("description", "Timeout; defaults to 120").put("minimum", 1).put("maximum", 600);
        schema.put("additionalProperties", false);
        this.parameters = schema;
    }

    static Path defaultJava() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Run a BeanShell 2.0 script on the agent's JRE with every bundled productivity library on the "
                + "classpath. Use it for Office, PDF, CSV, JSON/YAML, HTML, Markdown, archive, image, chart, "
                + "text, and math work, and for any scripting the host shell cannot do: jshell, javac, and "
                + "`java Script.java` are unavailable. Pass inline `script` for short work or `path` to a .bsh "
                + "file for reusable scripts; arguments arrive as `bsh.args`, and relative file paths resolve "
                + "against `working_directory`. The result fails with a nonzero exit code on parse errors, "
                + "evaluation errors (reported with file and line), and uncaught exceptions. After writing an "
                + "artifact, reopen it in the script and throw an exception if its contents are wrong.\n"
                + "BeanShell 2.0 accepts Java 1.4-style syntax plus for-each, autoboxing, and string switch:\n"
                + "- No generics: write `List rows = new ArrayList();` and cast elements when needed.\n"
                + "- No lambdas or method references: use anonymous inner classes.\n"
                + "- No try-with-resources: close streams and workbooks in `finally` blocks.\n"
                + "- No varargs calls: pass an explicit array, e.g. "
                + "`String.format(\"%s=%d\", new Object[] {k, v})`.\n"
                + "- `throw` accepts only Exception types: throw `IllegalStateException`, not `AssertionError`.\n"
                + "Typed declarations, scripted methods, and loose variables (`x = 1;`) are allowed; prefer "
                + "typed declarations for readability.";
    }

    @Override public ObjectNode parameters() { return parameters; }
    @Override public boolean requiresApproval() { return true; }

    @Override
    public String preview(JsonNode args) {
        try {
            return describe(args);
        } catch (IllegalArgumentException invalid) {
            return "run BeanShell with invalid arguments: " + ToolPreview.safeText(invalid.getMessage());
        }
    }

    private static String describe(JsonNode args) {
        String workingDirectory = nonBlank(args, "working_directory");
        String directory = ToolPreview.safeText(workingDirectory == null ? "." : workingDirectory);
        String arguments = scriptArgs(args).isEmpty() ? ""
                : " with args `" + ToolPreview.safeText(String.join(" ", scriptArgs(args))) + "`";
        String path = nonBlank(args, "path");
        if (path != null && nonBlank(args, "script") == null) {
            return "run BeanShell script `" + ToolPreview.safeText(path) + "` in `"
                    + directory + "`" + arguments;
        }
        return "run BeanShell in `" + directory + "`" + arguments + ": `"
                + ToolPreview.safeText(String.valueOf(nonBlank(args, "script"))) + "`";
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        return executeResult(args, null).output();
    }

    @Override
    public ToolResult executeResult(JsonNode args, String invocationId) throws Exception {
        String inlineScript = nonBlank(args, "script");
        String scriptPath = nonBlank(args, "path");
        if (inlineScript == null && scriptPath == null) {
            throw new IllegalArgumentException("Both script and path are empty: put BeanShell source in script, "
                    + "or a workspace-relative .bsh file in path");
        }
        if (inlineScript != null && scriptPath != null) {
            throw new IllegalArgumentException("Both script and path were given (path=\"" + scriptPath
                    + "\"): set path to \"\" to run the inline script, or script to \"\" to run the file");
        }
        boolean inline = inlineScript != null;
        int timeout = args.path("timeout_seconds").isMissingNode() ? 120 : args.path("timeout_seconds").asInt();
        if (timeout < 1 || timeout > 600) throw new IllegalArgumentException("timeout_seconds must be from 1 to 600");
        String directory = nonBlank(args, "working_directory");
        Path cwd = workspace.resolveExisting(directory == null ? "." : directory);
        if (!Files.isDirectory(cwd)) throw new IOException("Not a directory: " + workspace.display(cwd));

        Path jar = productivityJar.get();
        if (!Files.isRegularFile(jar)) {
            return ToolResult.error("Error: productivity bundle is unavailable at " + jar
                    + "; build it with `mvn -f productivity/pom.xml clean verify` or set JAVA_AGENT_PRODUCTIVITY_JAR");
        }

        byte[] stdin = null;
        String script;
        if (inline) {
            stdin = inlineScript.getBytes(StandardCharsets.UTF_8);
            if (stdin.length > MAX_INLINE_SCRIPT_BYTES) {
                throw new IllegalArgumentException("Inline script exceeds " + MAX_INLINE_SCRIPT_BYTES
                        + " bytes; write it to a .bsh file and pass path");
            }
            script = "-";
        } else {
            Path file = workspace.resolveExisting(scriptPath);
            if (!Files.isRegularFile(file)) throw new IOException("Not a file: " + workspace.display(file));
            script = file.toString();
        }

        List<String> argv = new ArrayList<>(List.of(java.toString(), "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8", "-jar", jar.toString(), script));
        argv.addAll(scriptArgs(args));
        return WorkspaceTools.runCaptured(argv, cwd, timeout, stdin);
    }

    private static List<String> scriptArgs(JsonNode args) {
        JsonNode values = args.path("args");
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalArgumentException("args must be an array of strings");
        List<String> result = new ArrayList<>();
        for (JsonNode value : (ArrayNode) values) {
            if (!value.isTextual()) throw new IllegalArgumentException("args must be an array of strings");
            result.add(value.asText());
        }
        return result;
    }

    /** Models often send every schema field and fill unused ones with "", so blank means absent. */
    private static String nonBlank(JsonNode args, String field) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return value.asText().isBlank() ? null : value.asText();
    }
}
