package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsCommandIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void managesSkillsWithoutApiAuthenticationOrModelCalls() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path state = temporary.resolve("state");

        JsonNode created = run(workspace, state, "skills", "create", "exact-skill");
        assertEquals("create", created.path("action").asText());
        assertTrue(created.path("reload").asBoolean());
        Path file = state.resolve("skills/exact-skill/SKILL.md");
        assertTrue(Files.isRegularFile(file));

        JsonNode listed = run(workspace, state, "skills", "list");
        assertTrue(listed.path("count").asInt() >= 1);
        JsonNode exact = json.missingNode();
        for (JsonNode skill : listed.path("skills")) {
            if (skill.path("name").asText().equals("exact-skill")) exact = skill;
        }
        assertEquals("exact-skill", exact.path("name").asText());
        assertTrue(exact.path("managed").asBoolean());

        JsonNode shown = run(workspace, state, "skills", "show", "exact-skill");
        assertTrue(shown.path("content").asText().contains("Instructions for this skill"));

        JsonNode removed = run(workspace, state, "skills", "remove", "exact-skill");
        assertEquals("exact-skill", removed.path("removed").asText());
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void installsOneFilteredSkillFromALocalPack() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("install-workspace"));
        Path state = temporary.resolve("install-state");
        Path pack = Files.createDirectory(temporary.resolve("pack"));
        writeSkill(pack.resolve("a"), "alpha");
        writeSkill(pack.resolve("b"), "beta");

        JsonNode installed = run(workspace, state, "skills", "install",
                pack.toString(), "--skill=beta");
        assertTrue(installed.path("status").asText().contains("beta"));
        assertTrue(Files.isRegularFile(state.resolve("skills/beta/SKILL.md")));
        assertFalse(Files.exists(state.resolve("skills/alpha")));
    }

    private JsonNode run(Path workspace, Path state, String... command) throws Exception {
        java.util.List<String> arguments = new java.util.ArrayList<>(java.util.List.of(command));
        arguments.add("--json");
        arguments.add("--workspace");
        arguments.add(workspace.toString());
        arguments.add("--session-root");
        arguments.add(state.toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int code = Main.run(arguments.toArray(String[]::new), Map.of(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        return json.readTree(output.toString(StandardCharsets.UTF_8));
    }

    private static void writeSkill(Path directory, String name) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + name + "\n---\nbody\n");
    }
}
