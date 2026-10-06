package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registry ranking, alias resolution, and the grouped help catalog. */
class SlashCommandsTest {
    @Test
    void filterRanksExactThenPrefixThenSubstring() {
        List<SlashCommands.Match> matches = SlashCommands.filter("/s");
        assertEquals("/status", matches.get(0).token, "registry order breaks rank ties");
        assertEquals("/stats", matches.get(1).token);
        assertTrue(matches.stream().anyMatch(match -> match.token.equals("/resume")),
                "substring rank still surfaces /resume");
        int resumeIndex = matches.indexOf(matches.stream()
                .filter(match -> match.token.equals("/resume")).findFirst().orElseThrow());
        assertTrue(resumeIndex > 1, "prefixes must precede substrings");
    }

    @Test
    void resumeIsTheOnlyBrowseAndResumeCommand() {
        assertEquals("/resume", SlashCommands.resolve("/resume").command);
        assertEquals("/resume", SlashCommands.resolve("/resume saved-id").command);
        assertEquals("/resume", SlashCommands.resolve("/resume last").command);
        assertNull(SlashCommands.resolve("/sessions"), "the retired name must not remain an alias");
        assertTrue(SlashCommands.filter("/sessions").isEmpty());
        assertTrue(!SlashCommands.catalog(null, 120, Ansi.of(false)).contains("/sessions"));
    }

    @Test
    void exactMatchBeatsShorterPrefix() {
        List<SlashCommands.Match> matches = SlashCommands.filter("/exit");
        assertEquals("/exit", matches.get(0).token);
    }

    @Test
    void aliasTokensMatchAndResolveToTheirSpec() {
        List<SlashCommands.Match> matches = SlashCommands.filter("/q");
        assertEquals(1, matches.size());
        assertEquals("/quit", matches.get(0).token);
        assertEquals("/exit", matches.get(0).spec.command);
        assertEquals("/exit", SlashCommands.resolve("/quit").command);
    }

    @Test
    void filterCapsAtEightRows() {
        assertTrue(SlashCommands.registry().size() > SlashCommands.MAX_MENU_ROWS);
        List<SlashCommands.Match> matches = SlashCommands.filter("/");
        assertEquals(SlashCommands.MAX_MENU_ROWS, matches.size());
    }

    @Test
    void resolveHonorsArgumentsAndRejectsUnknownCommands() {
        assertEquals("/resume", SlashCommands.resolve("/resume last").command);
        assertEquals("/mcp", SlashCommands.resolve("/mcp status").command);
        assertNull(SlashCommands.resolve("plain prompt"));
        assertNull(SlashCommands.resolve("/zz"));
        assertEquals("/permissions", SlashCommands.resolve(
                "/permissions remember allow write_file {\"path\":\"a\"}").command,
                "nested metadata must not steal dispatch from the base command parser");
        assertEquals("/permissions", SlashCommands.resolve("/permissions\trevoke 1").command);
    }

    @Test
    void permissionModesAreDiscoverableInHelpAndTabEntries() {
        String catalog = SlashCommands.catalog("permissions", 100, Ansi.of(false));
        assertTrue(catalog.contains("/permissions [ask|auto|yolo]"));
        assertTrue(catalog.contains("/permissions ask"));
        assertTrue(catalog.contains("/permissions auto"));
        assertTrue(catalog.contains("/permissions yolo"));
        assertTrue(SlashCommands.filter("/permissions ").stream()
                .anyMatch(match -> match.token.equals("/permissions yolo")));
    }

    @Test
    void catalogGroupsByCategoryWithDimHeaders() {
        String catalog = SlashCommands.catalog(null, 80, Ansi.of(false));
        List<String> lines = List.of(catalog.split("\n", -1));
        assertTrue(lines.contains("General"));
        assertTrue(lines.contains("Session"));
        assertTrue(lines.contains("Model"));
        assertTrue(lines.contains("Permissions"));
        assertTrue(lines.contains("MCP"));
        assertTrue(catalog.indexOf("General") < catalog.indexOf("/clear"));
        assertTrue(catalog.indexOf("Session") < catalog.indexOf("/new"));
        assertTrue(catalog.endsWith("\n"));
    }

    @Test
    void catalogAlignsUsageAndDimsDescriptions() {
        String catalog = SlashCommands.catalog("", 80, Ansi.of(true));
        assertTrue(catalog.contains("  /resume [id|last]  "),
                "usage column is padded: " + catalog);
        assertTrue(catalog.contains("\u001b[2mpick or resume a saved session\u001b[0m"));
    }

    @Test
    void catalogQueryFiltersAcrossCommandAndDescription() {
        String byCommand = SlashCommands.catalog("rename", 80, Ansi.of(false));
        assertTrue(byCommand.contains("/rename"));
        assertTrue(!byCommand.contains("/resume"));

        String byDescription = SlashCommands.catalog("health", 80, Ansi.of(false));
        assertTrue(byDescription.contains("/mcp"));
        assertTrue(!byDescription.contains("/rename"));

        String byCategoryLabel = SlashCommands.catalog("session", 80, Ansi.of(false));
        assertTrue(byCategoryLabel.contains("/resume"));

        String multiToken = SlashCommands.catalog("saved resume", 80, Ansi.of(false));
        assertTrue(multiToken.contains("/resume"));
        assertTrue(!multiToken.contains("/recover"));
    }

    @Test
    void catalogReportsEmptyMatches() {
        assertEquals("No commands match '/zz'. Try /help.",
                SlashCommands.catalog("/zz", 80, Ansi.of(false)));
    }

    @Test
    void everyWiredCommandIsPresent() {
        String catalog = SlashCommands.catalog(null, 120, Ansi.of(false));
        for (String command : new String[]{"/help", "/clear", "/new", "/resume [id|last]",
                "/recover <id>", "/rename <title>", "/mcp [list|status]", "/exit", "/model",
                "/permissions", "/permissions remember <allow|deny> <tool-name> <arguments-json>",
                "/permissions revoke <id>", "/status"}) {
            assertTrue(catalog.contains(command), "missing " + command);
        }
    }

    @Test
    void statsAndCompactAreRegisteredInTheirCategories() {
        assertEquals("/stats", SlashCommands.resolve("/stats").command);
        assertEquals(SlashCommands.Category.GENERAL, SlashCommands.resolve("/stats").category);
        assertEquals("/compact", SlashCommands.resolve("/compact").command);
        assertEquals(SlashCommands.Category.SESSION, SlashCommands.resolve("/compact").category);
        String catalog = SlashCommands.catalog(null, 120, Ansi.of(false));
        assertTrue(catalog.contains("/stats"));
        assertTrue(catalog.contains("/compact"));
    }
}
