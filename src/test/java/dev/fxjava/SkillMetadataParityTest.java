package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relevant metadata contracts ported from fx/core/skills/skill_contract.zig. */
class SkillMetadataParityTest {
    @Test
    void parsesFullPartialLegacyCrLfQuotedAndExactEofForms() {
        var full = SkillMetadata.parse("---\nname: my-skill\ndescription: Helps with testing\n---\n\n# Body");
        assertEquals(SkillMetadata.Status.VALID, full.status());
        assertEquals("my-skill", full.name());
        assertEquals("Helps with testing", full.description());
        assertEquals("# Body", full.body());

        var partial = SkillMetadata.parse("---\nname: partial\n---\nBody");
        assertEquals(SkillMetadata.Status.VALID, partial.status());
        assertNull(partial.description());
        assertEquals("Body", partial.body());

        var legacy = SkillMetadata.parse("# Just Markdown\n\nSome content.");
        assertEquals(SkillMetadata.Status.NO_FRONTMATTER, legacy.status());
        assertEquals("legacy", legacy.resolve("legacy").name());
        assertEquals("", legacy.resolve("legacy").description());

        var crlf = SkillMetadata.parse("---\r\nname: \"windows-newline\"\r\ndescription: 'quoted'\r\n---\r\nBody");
        assertEquals(SkillMetadata.Status.VALID, crlf.status());
        assertEquals("windows-newline", crlf.name());
        assertEquals("quoted", crlf.description());
        assertEquals("Body", crlf.body());

        assertEquals(SkillMetadata.Status.VALID,
                SkillMetadata.parse("---\nname: eof-close\n---").status());
    }

    @Test
    void decodesSupportedFoldedLiteralAndStripDescriptions() {
        assertDescription("---\nname: folded\ndescription: >\n  Fold this\n  onto one line.\n\n  Keep this paragraph.\n---\nBody",
                "Fold this onto one line.\n\nKeep this paragraph.\n");
        assertDescription("---\nname: strip\ndescription: >-\n  Fold without\n  a trailing newline.\n---\nBody",
                "Fold without a trailing newline.");
        assertDescription("---\nname: literal\ndescription: |\n  Keep this\n  on two lines.\n---\nBody",
                "Keep this\non two lines.\n");
        assertDescription("---\r\nname: crlf\r\ndescription: |\r\n  first\r\n  second\r\n---\r\nBody",
                "first\nsecond\n");
        assertDescription("---\ndescription: >-\n  first\n    extra indent\nname: after-block\n---\nBody",
                "first   extra indent");
        assertDescription("---\nname: empty\ndescription: >\n\n---\nBody", "");
    }

    @Test
    void enforcesByteBoundsAndIgnoresUnknownMetadata() {
        String exactName = "n".repeat(SkillMetadata.MAX_NAME_BYTES);
        assertEquals(SkillMetadata.Status.VALID,
                SkillMetadata.parse("---\nname: " + exactName + "\n---\n").status());
        assertInvalid("---\nname: " + exactName + "n\n---\n", SkillMetadata.Cause.NAME_TOO_LONG);

        String exactDescription = "d".repeat(SkillMetadata.MAX_DESCRIPTION_BYTES);
        assertEquals(SkillMetadata.Status.VALID,
                SkillMetadata.parse("---\nname: valid\ndescription: " + exactDescription + "\n---\n").status());
        assertInvalid("---\nname: valid\ndescription: " + exactDescription + "d\n---\n",
                SkillMetadata.Cause.DESCRIPTION_TOO_LONG);

        var ignored = SkillMetadata.parse("---\nignored\nname: known\nextra: value\ndescription: useful\n---\nBody");
        assertEquals(SkillMetadata.Status.VALID, ignored.status());
        assertEquals("known", ignored.name());
        assertEquals("useful", ignored.description());
    }

    @Test
    void rejectsMalformedRecognizedMetadataAndUnsafeFallbacks() {
        assertInvalid("---\nname: unclosed", SkillMetadata.Cause.MISSING_CLOSING_DELIMITER);
        assertInvalid("---\ndescription: missing name\n---\nBody", SkillMetadata.Cause.MISSING_NAME);
        assertInvalid("---\nname: first\nname: second\n---\nBody",
                SkillMetadata.Cause.DUPLICATE_RECOGNIZED_KEY);
        assertInvalid("---\nname: ../unsafe\n---\nBody", SkillMetadata.Cause.INVALID_NAME);
        assertInvalid("---\nname: \"unterminated\n---\nBody", SkillMetadata.Cause.MALFORMED_QUOTE);
        assertInvalid("---\nname: multiline\ndescription: |2\n---\nBody",
                SkillMetadata.Cause.UNSUPPORTED_MULTILINE);
        assertInvalid("---\nname: workflow\n  continued name\ndescription: helper\n---\nBody",
                SkillMetadata.Cause.UNSUPPORTED_MULTILINE);
        assertInvalid("---\nname: tabbed\ndescription: >\n\tvalue\n---\n",
                SkillMetadata.Cause.UNSUPPORTED_MULTILINE);
        assertInvalid("---\nname: shallow\ndescription: >\n   first\n  smaller indent\n---\n",
                SkillMetadata.Cause.UNSUPPORTED_MULTILINE);
        assertInvalid("---\nname: control\u0001byte\n---\nBody", SkillMetadata.Cause.CONTROL_BYTE);
        assertNull(SkillMetadata.parse("legacy").resolve("../unsafe"));
    }

    @Test
    void rejectsInvalidUtf8BeforeDiscovery() {
        byte[] prefix = "---\nname: invalid".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + 6);
        bytes[prefix.length] = (byte) 0xff;
        byte[] suffix = "\n---\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(suffix, 0, bytes, prefix.length + 1, suffix.length);
        var parsed = SkillMetadata.parse(bytes);
        assertEquals(SkillMetadata.Status.INVALID, parsed.status());
        assertEquals(SkillMetadata.Cause.INVALID_UTF8, parsed.cause());
    }

    private static void assertDescription(String content, String expected) {
        var parsed = SkillMetadata.parse(content);
        assertEquals(SkillMetadata.Status.VALID, parsed.status(), () -> String.valueOf(parsed.cause()));
        assertEquals(expected, parsed.description());
    }

    private static void assertInvalid(String content, SkillMetadata.Cause cause) {
        var parsed = SkillMetadata.parse(content);
        assertEquals(SkillMetadata.Status.INVALID, parsed.status());
        assertEquals(cause, parsed.cause());
    }
}
