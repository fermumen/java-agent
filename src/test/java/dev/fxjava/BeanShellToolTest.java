package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BeanShellToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;

    @Test
    void catalogCarriesBeanShellSyntaxLimitsAndMinimalHostWarnings() throws Exception {
        List<Tool> tools = WorkspaceTools.create(workspace, workspace.resolve(".state"));
        Tool beanshell = named(tools, "beanshell");

        assertTrue(beanshell.requiresApproval());
        String description = beanshell.description();
        assertTrue(description.contains("jshell, javac, and `java Script.java` are unavailable"));
        assertTrue(description.contains("No generics"));
        assertTrue(description.contains("No lambdas"));
        assertTrue(description.contains("No try-with-resources"));
        assertTrue(description.contains("String.format(\"%s=%d\", new Object[] {k, v})"));
        assertTrue(description.contains("`throw` accepts only Exception types"));
        assertTrue(description.contains("bsh.args"));
        assertTrue(description.contains("nonzero exit code"));

        for (String shell : List.of("run_command", "terminal")) {
            String shellDescription = named(tools, shell).description();
            assertTrue(shellDescription.contains("git, rg, grep"), shell);
            assertTrue(shellDescription.contains("PowerShell may be missing or restricted"), shell);
            assertTrue(shellDescription.contains("beanshell tool"), shell);
        }
    }

    @Test
    void pipesInlineScriptsAndPassesArgumentsToTheRunnerJar() throws Exception {
        Files.createDirectory(workspace.resolve("out"));
        BeanShellTool tool = tool(fakeRunnerJar());

        ToolResult result = tool.executeResult(args("{\"script\":\"print(\\\"héllo\\\");\","
                + "\"args\":[\"a b\",\"c\"],\"working_directory\":\"out\"}"), null);

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("script=-"), result.output());
        assertTrue(result.output().contains("args=[a b, c]"), result.output());
        assertTrue(result.output().contains("cwd=out"), result.output());
        assertTrue(result.output().contains("headless=true"), result.output());
        assertTrue(result.output().contains("api-key=null"), result.output());
        assertTrue(result.output().contains("stdin=print(\"héllo\");"), result.output());
        assertTrue(result.output().endsWith("Exit code: 0"), result.output());
    }

    @Test
    void runsWorkspaceScriptFilesAndReportsNonzeroExitAsError() throws Exception {
        Files.writeString(workspace.resolve("job.bsh"), "print(1);");
        BeanShellTool tool = tool(fakeRunnerJar());

        ToolResult file = tool.executeResult(args("{\"path\":\"job.bsh\"}"), null);
        assertFalse(file.isError(), file.output());
        assertTrue(file.output().contains("script=" + workspace.toRealPath().resolve("job.bsh")), file.output());

        ToolResult failed = tool.executeResult(args("{\"script\":\"fail();\"}"), null);
        assertTrue(failed.isError(), failed.output());
        assertTrue(failed.output().endsWith("Exit code: 3"), failed.output());
    }

    @Test
    void treatsBlankFieldsAsAbsentLikeModelsThatFillEverySchemaField() throws Exception {
        Files.writeString(workspace.resolve("create_inventory.bsh"), "print(1);");
        BeanShellTool tool = tool(fakeRunnerJar());

        ToolResult inline = tool.executeResult(args("{\"script\":\"print(\\\"test\\\");\",\"path\":\"\","
                + "\"args\":[],\"working_directory\":\"\",\"timeout_seconds\":120}"), null);
        assertFalse(inline.isError(), inline.output());
        assertTrue(inline.output().contains("stdin=print(\"test\");"), inline.output());

        ToolResult file = tool.executeResult(args("{\"script\":\"\",\"path\":\"create_inventory.bsh\","
                + "\"args\":[],\"working_directory\":\".\",\"timeout_seconds\":120}"), null);
        assertFalse(file.isError(), file.output());
        assertTrue(file.output().contains("create_inventory.bsh"), file.output());
        assertEquals("run BeanShell script `create_inventory.bsh` in `.`",
                tool.preview(args("{\"script\":\" \",\"path\":\"create_inventory.bsh\",\"working_directory\":\"\"}")));

        IllegalArgumentException both = assertThrows(IllegalArgumentException.class, () -> tool.executeResult(
                args("{\"script\":\"print(1);\",\"path\":\"create_inventory.bsh\"}"), null));
        assertTrue(both.getMessage().contains("path=\"create_inventory.bsh\""), both.getMessage());
        assertTrue(both.getMessage().contains("set path to \"\" to run the inline script"), both.getMessage());
        IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
                () -> tool.executeResult(args("{\"script\":\"\",\"path\":\"  \"}"), null));
        assertTrue(neither.getMessage().contains("Both script and path are empty"), neither.getMessage());
    }

    @Test
    void fileToolsTreatBlankOptionalPathsAsTheWorkspaceRoot() throws Exception {
        Files.writeString(workspace.resolve("visible.txt"), "x");
        Tool list = named(WorkspaceTools.create(workspace, workspace.resolve(".state")), "list_files");

        String listing = list.execute(args("{\"path\":\"\"}"));
        assertTrue(listing.contains("visible.txt"), listing);
    }

    @Test
    void rejectsAmbiguousOrInvalidRequests() throws Exception {
        BeanShellTool tool = tool(fakeRunnerJar());

        assertThrows(IllegalArgumentException.class, () -> tool.executeResult(args("{}"), null));
        assertThrows(IllegalArgumentException.class,
                () -> tool.executeResult(args("{\"script\":\"x\",\"path\":\"y.bsh\"}"), null));
        assertThrows(IllegalArgumentException.class,
                () -> tool.executeResult(args("{\"script\":\"x\",\"args\":[1]}"), null));
        assertThrows(IllegalArgumentException.class,
                () -> tool.executeResult(args("{\"script\":\"x\",\"timeout_seconds\":0}"), null));
        String oversized = "x".repeat(BeanShellTool.MAX_INLINE_SCRIPT_BYTES + 1);
        ObjectNode large = JSON.createObjectNode().put("script", oversized);
        assertThrows(IllegalArgumentException.class, () -> tool.executeResult(large, null));
    }

    @Test
    void reportsMissingProductivityBundle() throws Exception {
        Path missing = workspace.resolve("absent").resolve("productivity.jar");
        ToolResult result = tool(missing).executeResult(args("{\"script\":\"print(1);\"}"), null);

        assertTrue(result.isError());
        assertTrue(result.output().contains("productivity bundle is unavailable at " + missing), result.output());
    }

    @Test
    void previewShowsInlineScriptPathAndArguments() throws Exception {
        BeanShellTool tool = tool(workspace.resolve("productivity.jar"));

        assertEquals("run BeanShell in `.` with args `in.csv`: `int x = 1;\\nprint(x);`",
                tool.preview(args("{\"script\":\"int x = 1;\\nprint(x);\",\"args\":[\"in.csv\"]}")));
        assertEquals("run BeanShell script `jobs/report.bsh` in `out`",
                tool.preview(args("{\"path\":\"jobs/report.bsh\",\"working_directory\":\"out\"}")));
        assertTrue(tool.preview(args("{\"script\":\"x\",\"args\":\"bad\"}")).contains("invalid arguments"));
    }

    @Test
    void runsRealBundleWhenBuilt() throws Exception {
        Path bundle = Agent.productivityJar();
        assumeTrue(Files.isRegularFile(bundle), "productivity.jar has not been built");
        BeanShellTool tool = tool(bundle);

        ToolResult ok = tool.executeResult(args("{\"script\":\"import java.util.*;\\nList rows = new ArrayList();\\n"
                + "rows.add(bsh.args[0]);\\nprint(String.format(\\\"%s=%d\\\", new Object[] {rows.get(0), 7}));\","
                + "\"args\":[\"k\"]}"), null);
        assertFalse(ok.isError(), ok.output());
        assertTrue(ok.output().contains("k=7"), ok.output());

        ToolResult generics = tool.executeResult(args("{\"script\":\"java.util.List<String> x = null;\"}"), null);
        assertTrue(generics.isError(), generics.output());
        assertTrue(generics.output().contains("BeanShell evaluation error at <stdin>:1"), generics.output());

        ToolResult thrown = tool.executeResult(args("{\"script\":\"throw new IllegalStateException(\\\"bad data\\\");\"}"), null);
        assertTrue(thrown.isError(), thrown.output());
        assertTrue(thrown.output().contains("bad data"), thrown.output());
    }

    private BeanShellTool tool(Path jar) throws Exception {
        return new BeanShellTool(new WorkspaceTools.Workspace(workspace), BeanShellTool.defaultJava(), () -> jar);
    }

    private Path fakeRunnerJar() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, FakeBshRunner.class.getName());
        String testClasses = FakeBshRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI().toString();
        attributes.put(Attributes.Name.CLASS_PATH, testClasses);
        Path jar = Files.createTempFile("fake-productivity-", ".jar");
        jar.toFile().deleteOnExit();
        try (OutputStream output = Files.newOutputStream(jar); JarOutputStream ignored = new JarOutputStream(output, manifest)) {
            // Manifest only: the runner class loads from the test-classes directory.
        }
        return jar;
    }

    private static JsonNode args(String json) throws Exception {
        return JSON.readTree(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Tool named(List<Tool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }
}
