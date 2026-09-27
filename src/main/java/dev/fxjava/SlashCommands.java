package dev.fxjava;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Typed slash-command registry mirroring fx's command_specs.zig presentation:
 * categories with labels, usage hints, aliases, and completion ranking where
 * exact matches beat prefixes and prefixes beat substrings. Also renders the
 * grouped /help catalog with an optional cross-command query filter.
 */
final class SlashCommands {
    static final int MAX_MENU_ROWS = 8;

    enum Category {
        GENERAL("General"),
        SESSION("Session"),
        MODEL("Model"),
        PERMISSIONS("Permissions"),
        MCP("MCP"),
        MEDIA("Media");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    static final class Spec {
        final String command;
        final List<String> aliases;
        final String args;
        final String description;
        final Category category;

        Spec(String command, List<String> aliases, String args, String description, Category category) {
            this.command = command;
            this.aliases = List.copyOf(aliases);
            this.args = args;
            this.description = description;
            this.category = category;
        }

        String usage() {
            return args.isEmpty() ? command : command + " " + args;
        }
    }

    /** A ranked completion hit: the spec plus the concrete token that matched. */
    static final class Match {
        final Spec spec;
        final String token;

        Match(Spec spec, String token) {
            this.spec = spec;
            this.token = token;
        }
    }

    private static final List<Spec> REGISTRY = List.of(
            new Spec("/help", List.of(), "[query]", "show the command catalog", Category.GENERAL),
            new Spec("/clear", List.of(), "", "clear the conversation history", Category.GENERAL),
            new Spec("/status", List.of(), "", "workspace, model, mode, and session summary", Category.GENERAL),
            new Spec("/exit", List.of("/quit"), "", "leave the shell", Category.GENERAL),
            new Spec("/new", List.of(), "", "start a fresh saved session", Category.SESSION),
            new Spec("/sessions", List.of(), "", "list saved sessions", Category.SESSION),
            new Spec("/resume", List.of(), "<id|last>", "resume a saved session", Category.SESSION),
            new Spec("/recover", List.of(), "<id>", "recover an interrupted session", Category.SESSION),
            new Spec("/rename", List.of(), "<title>", "rename the current session", Category.SESSION),
            new Spec("/model", List.of(), "[<id> [--save]]", "show or change the active model", Category.MODEL),
            new Spec("/effort", List.of(), "[<level|default> [--save]]",
                    "show or change reasoning effort", Category.MODEL),
            new Spec("/permissions", List.of(), "[ask|auto|yolo]", "show or change permission mode and rules",
                    Category.PERMISSIONS),
            new Spec("/permissions ask", List.of(), "", "prompt before approval-required actions",
                    Category.PERMISSIONS),
            new Spec("/permissions auto", List.of(), "", "auto-approve safe actions; deny other actions",
                    Category.PERMISSIONS),
            new Spec("/permissions yolo", List.of(), "", "bypass all tool approvals for this run",
                    Category.PERMISSIONS),
            new Spec("/permissions remember", List.of(), "<allow|deny> <tool-name> <arguments-json>",
                    "remember an exact rule for this saved session", Category.PERMISSIONS),
            new Spec("/permissions revoke", List.of(), "<id>", "revoke a remembered rule by id",
                    Category.PERMISSIONS),
            new Spec("/mcp", List.of(), "[list|status]", "show MCP server health", Category.MCP),
            new Spec("/stats", List.of(), "", "token totals for this and recent saved sessions",
                    Category.GENERAL),
            new Spec("/compact", List.of(), "", "summarize older history, keeping recent exchanges verbatim",
                    Category.SESSION),
            new Spec("/image", List.of(), "<path|clear>", "attach a local image to your next message",
                    Category.MEDIA));

    static List<Spec> registry() {
        return REGISTRY;
    }

    /** Resolves the first whitespace-delimited token, honoring aliases. */
    static Spec resolve(String line) {
        String token = firstToken(line);
        if (token == null) return null;
        for (Spec spec : REGISTRY) {
            if (token.equals(spec.command)) return spec;
            for (String alias : spec.aliases) {
                if (token.equals(alias)) return spec;
            }
        }
        return null;
    }

    /**
     * Ranked completion matches for a composer token such as "/se": exact,
     * then prefix, then substring after the first character, registry order
     * within each rank, capped at {@link #MAX_MENU_ROWS}.
     */
    static List<Match> filter(String prefix) {
        List<Match> matches = new ArrayList<>();
        for (int rank = 0; rank <= 2 && matches.size() < MAX_MENU_ROWS; rank++) {
            for (Spec spec : REGISTRY) {
                if (matches.size() == MAX_MENU_ROWS) break;
                if (containsSpec(matches, spec)) continue;
                String token = matchAtRank(spec, prefix, rank);
                if (token != null) matches.add(new Match(spec, token));
            }
        }
        return List.copyOf(matches);
    }

    private static boolean containsSpec(List<Match> matches, Spec spec) {
        for (Match match : matches) {
            if (match.spec == spec) return true;
        }
        return false;
    }

    private static String matchAtRank(Spec spec, String prefix, int rank) {
        String token = rankOf(spec.command, prefix) == rank ? spec.command : null;
        for (String alias : spec.aliases) {
            if (token != null) break;
            if (rankOf(alias, prefix) == rank) token = alias;
        }
        return token;
    }

    private static int rankOf(String candidate, String prefix) {
        if (candidate.equals(prefix)) return 0;
        if (candidate.startsWith(prefix)) return 1;
        if (prefix.length() <= 1 || candidate.length() <= 1) return -1;
        return candidate.indexOf(prefix.substring(1), 1) > 0 ? 2 : -1;
    }

    /**
     * Grouped help directory: one dim category header per non-empty group with
     * aligned usage and dim descriptions. Every query token must appear inside
     * the command, an alias, the usage, the description, or the category label.
     */
    static String catalog(String query, int columns, Ansi ansi) {
        List<List<Spec>> groups = new ArrayList<>();
        List<Category> categories = new ArrayList<>();
        for (Category category : Category.values()) {
            List<Spec> specs = new ArrayList<>();
            for (Spec spec : REGISTRY) {
                if (spec.category == category && matchesQuery(spec, query)) {
                    specs.add(spec);
                }
            }
            if (!specs.isEmpty()) {
                categories.add(category);
                groups.add(specs);
            }
        }
        if (groups.isEmpty()) {
            String needle = query == null || query.isBlank() ? "" : query.strip();
            return "No commands match '" + needle + "'. Try /help.";
        }
        StringBuilder out = new StringBuilder();
        for (int group = 0; group < groups.size(); group++) {
            if (group > 0) out.append('\n');
            out.append(ansi.dim()).append(categories.get(group).label()).append(ansi.reset()).append('\n');
            int usageWidth = groups.get(group).stream().mapToInt(spec -> spec.usage().length()).max().orElse(0);
            for (Spec spec : groups.get(group)) {
                int descriptionBudget = Math.max(1, columns - 4 - usageWidth);
                String description = ToolGroupLines.truncate(spec.description, descriptionBudget);
                out.append("  ").append(pad(spec.usage(), usageWidth)).append("  ")
                        .append(ansi.dim()).append(description).append(ansi.reset()).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean matchesQuery(Spec spec, String query) {
        if (query == null || query.isBlank()) return true;
        String haystack = String.join("\n", spec.command, String.join("\n", spec.aliases),
                spec.usage(), spec.description, spec.category.label()).toLowerCase(Locale.ROOT);
        for (String token : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    private static String firstToken(String line) {
        if (line == null) return null;
        String stripped = line.strip();
        for (int index = 0; index < stripped.length(); index++) {
            if (Character.isWhitespace(stripped.charAt(index))) return stripped.substring(0, index);
        }
        return stripped;
    }

    private static String pad(String value, int width) {
        StringBuilder out = new StringBuilder(value);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    private SlashCommands() {
    }
}
