package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallSkillToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void atomicallyInstallsLocalSkillAndExistingReaderRediscoversIt() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");
        Path source = Files.createDirectories(temporary.resolve("source/workflow"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: workflow\ndescription: Test workflow\n---\n\nSteps\n");
        Files.writeString(source.resolve("reference.md"), "Reference\n");
        SkillTool reader = SkillTool.create(workspace, state);
        InstallSkillTool installer = new InstallSkillTool(workspace, state);

        String installed = installer.execute(json.createObjectNode().put("source", source.toString()));
        assertTrue(installed.contains("Installed skill workflow"));
        assertTrue(Files.isRegularFile(state.resolve("skills/workflow/SKILL.md")));
        assertTrue(reader.execute(json.createObjectNode().put("name", "workflow")).contains("Steps"));
        assertTrue(reader.execute(json.createObjectNode().put("name", "workflow").put("resource", "reference.md"))
                .contains("Reference"));
        assertThrows(IOException.class,
                () -> installer.execute(json.createObjectNode().put("source", source.toString())));
    }

    @Test
    void multiSkillSourceRequiresExactFilterAndRejectsSymlinkSources() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-two"));
        Path state = temporary.resolve("state-two");
        Path source = Files.createDirectory(temporary.resolve("repository"));
        writeSkill(source.resolve("a"), "alpha");
        writeSkill(source.resolve("b"), "beta");
        InstallSkillTool installer = new InstallSkillTool(workspace, state);

        assertTrue(assertThrows(IOException.class,
                () -> installer.execute(json.createObjectNode().put("source", source.toString())))
                .getMessage().contains("Multiple"));
        assertTrue(installer.execute(json.createObjectNode().put("source", source.toString()).put("skill", "beta"))
                .contains("beta"));

        Path link = temporary.resolve("source-link");
        try {
            Files.createSymbolicLink(link, source);
            assertThrows(IOException.class,
                    () -> installer.execute(json.createObjectNode().put("source", link.toString())));
        } catch (UnsupportedOperationException ignored) { }
    }

    private static void writeSkill(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + name + "\n---\n");
    }
}
