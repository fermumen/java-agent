package dev.fxjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void discoversFrontmatterAndReadsRelativeResources() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path skill = workspace.resolve("skills/review");
        Files.createDirectories(skill.resolve("references"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: review\ndescription: Review changes\n---\n\nInstructions\n");
        Files.writeString(skill.resolve("references/checklist.md"), "one\ntwo\n");

        SkillTool tool = SkillTool.create(workspace, temporary.resolve("state"));
        ObjectNode arguments = json.createObjectNode().put("name", "review")
                .put("location", skill.toRealPath().toString())
                .put("resource", "references/checklist.md");
        assertEquals("one\ntwo\n\n[end]", tool.execute(arguments));
        assertTrue(SkillTool.catalog(workspace, temporary.resolve("state"))
                .contains("review: Review changes"));
    }

    @Test
    void workspaceSkillWinsOverManagedDuplicate() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path local = writeSkill(workspace.resolve("skills/shared"), "shared", "local", "local body");
        Path state = temporary.resolve("state");
        writeSkill(state.resolve("skills/shared"), "shared", "managed", "managed body");

        SkillTool tool = SkillTool.create(workspace, state);
        String result = tool.execute(json.createObjectNode().put("name", "shared"));
        assertTrue(result.contains("local body"));
        assertTrue(SkillTool.catalog(workspace, state).contains(local.toRealPath().toString()));
    }

    @Test
    void rejectsMismatchedLocationTraversalAndUnknownName() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        writeSkill(workspace.resolve("skills/safe"), "safe", "safe", "body");
        SkillTool tool = SkillTool.create(workspace, temporary.resolve("state"));

        assertThrows(IOException.class, () -> tool.execute(json.createObjectNode()
                .put("name", "safe").put("location", temporary.toString())));
        assertThrows(IOException.class, () -> tool.execute(json.createObjectNode()
                .put("name", "safe").put("resource", "../outside.txt")));
        assertThrows(IOException.class, () -> tool.execute(json.createObjectNode().put("name", "missing")));
    }

    @Test
    void returnsBoundedChunksWithNextOffset() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path skill = writeSkill(workspace.resolve("skills/large"), "large", "large", "x".repeat(60_000));
        SkillTool tool = SkillTool.create(workspace, temporary.resolve("state"));

        String first = tool.execute(json.createObjectNode().put("name", "large"));
        assertTrue(first.contains("[next_offset=51200]"));
        String remainder = tool.execute(json.createObjectNode().put("name", "large").put("offset", 51_200));
        assertTrue(remainder.endsWith("[end]"));
        assertTrue(skill.toRealPath().startsWith(workspace.toRealPath()));
    }

    private Path writeSkill(Path directory, String name, String description, String body) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: "
                + description + "\n---\n\n" + body);
        return directory;
    }
}
