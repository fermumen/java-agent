package dev.fxjava;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Managed create/remove cases ported from fx/builtins/skills.zig. */
class SkillManagerParityTest {
    @TempDir
    Path temporary;

    @Test
    void createsExactTemplateInAMissingManagedRootAndRemovesItsTree() throws Exception {
        SkillManager manager = new SkillManager(temporary.resolve("missing/state"));
        Path file = manager.create("exact-skill");
        assertEquals("---\nname: exact-skill\ndescription: Describe when this skill should activate\n---\n\n"
                + "# exact-skill\n\nInstructions for this skill...\n", Files.readString(file));
        Files.createDirectories(file.getParent().resolve("assets"));
        Files.writeString(file.getParent().resolve("assets/data.txt"), "asset");
        assertTrue(manager.owns(file.getParent()));

        manager.remove("exact-skill");
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void rejectsInvalidNamesAndCompatibilityDirectories() throws Exception {
        SkillManager manager = new SkillManager(temporary.resolve("state"));
        for (String name : new String[]{"", ".", "..", "nested/name", "nested\\name", "/absolute"}) {
            assertThrows(IllegalArgumentException.class, () -> manager.create(name));
            assertThrows(IllegalArgumentException.class, () -> manager.remove(name));
        }
        Path compatibility = Files.createDirectories(temporary.resolve("workspace/skills/review"));
        Files.writeString(compatibility.resolve("SKILL.md"), "body");
        manager.create("managed");
        assertFalse(manager.owns(compatibility));
    }

    @Test
    void refusesSymlinkedManagedRootsWhenSupported() throws Exception {
        Path external = Files.createDirectory(temporary.resolve("external"));
        Path state = Files.createDirectory(temporary.resolve("link-state"));
        try {
            Files.createSymbolicLink(state.resolve("skills"), external);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        SkillManager manager = new SkillManager(state);
        assertTrue(assertThrows(IOException.class, () -> manager.create("safe"))
                .getMessage().contains("unsafe"));
    }
}
