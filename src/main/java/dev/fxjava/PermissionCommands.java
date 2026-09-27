package dev.fxjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shared /permissions command handling for the raw shell and the legacy
 * fallback loop, ported from fx's "/permissions remember" contract: bare
 * invocations print the mode plus a stable-sorted table of the active saved
 * session's exact rules, remember stores one allow/deny rule from arbitrary
 * arguments JSON, and revoke removes one by its stable id. Malformed input
 * always earns a usage line rather than an error dump; output is dim through
 * {@link Ansi} on TTY paths and byte-identical plain text otherwise.
 */
final class PermissionCommands {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REMEMBER_USAGE =
            "Usage: /permissions remember <allow|deny> <tool-name> <arguments-json>";
    private static final String REVOKE_USAGE = "Usage: /permissions revoke <id>";
    private static final String GENERAL_USAGE =
            "Usage: /permissions [ask|auto|yolo | remember <allow|deny> <tool-name> <arguments-json> | revoke <id>]";

    private PermissionCommands() {
    }

    /** Handles one /permissions invocation; {@code argument} is the trimmed remainder. */
    static void handle(SessionRuntime session, String argument, String modeLabel,
                       int grantCount, Ansi ansi, PrintStream out) {
        handle(session, argument, modeLabel, grantCount, ansi, out, null);
    }

    static void handle(SessionRuntime session, String argument, String modeLabel,
                       int grantCount, Ansi ansi, PrintStream out,
                       Consumer<PermissionMode> modeSetter) {
        String rest = argument == null ? "" : argument.strip();
        if (rest.isEmpty()) {
            summary(session, modeLabel, grantCount, ansi, out);
            return;
        }
        int separator = firstWhitespace(rest);
        String action = separator < 0 ? rest : rest.substring(0, separator);
        String remainder = separator < 0 ? "" : rest.substring(separator);
        PermissionMode requested = permissionMode(action);
        if (requested != null) {
            if (!remainder.isBlank() || modeSetter == null) {
                out.println(GENERAL_USAGE);
            } else {
                modeSetter.accept(requested);
                if (requested == PermissionMode.YOLO) {
                    out.println("YOLO enabled: all tool approvals and remembered permission rules are bypassed for this run.");
                } else {
                    out.println("Permission mode set to " + requested.name().toLowerCase(Locale.ROOT) + ".");
                }
            }
            return;
        }
        if (action.toLowerCase(Locale.ROOT).equals("remember")) {
            remember(session, remainder, out);
            return;
        }
        if (action.toLowerCase(Locale.ROOT).equals("revoke")) {
            revoke(session, remainder, out);
            return;
        }
        out.println(GENERAL_USAGE);
    }

    private static void summary(SessionRuntime session, String modeLabel, int grantCount,
                                Ansi ansi, PrintStream out) {
        SessionRules rules = session.rules();
        List<SessionRules.Rule> active = rules == null ? List.of() : rules.all();
        out.println("mode=" + modeLabel + " grants=" + grantCount + " rules=" + active.size());
        out.println(GENERAL_USAGE);
        if (!session.persistent()) {
            out.println(ansi.dim() + "Persistent rules need a saved session (--no-save)." + ansi.reset());
            return;
        }
        if (active.isEmpty()) {
            out.println(ansi.dim() + "No rules remembered for this session." + ansi.reset());
            return;
        }
        int idWidth = 0;
        int kindWidth = 0;
        int toolWidth = 0;
        for (SessionRules.Rule rule : active) {
            idWidth = Math.max(idWidth, rule.id.length());
            kindWidth = Math.max(kindWidth, rule.kind.label().length());
            toolWidth = Math.max(toolWidth, rule.tool.length());
        }
        out.println(ansi.dim() + pad("ID", idWidth) + "  " + pad("KIND", kindWidth)
                + "  " + pad("TOOL", toolWidth) + "  ARGUMENTS" + ansi.reset());
        for (SessionRules.Rule rule : active) {
            out.println(pad(rule.id, idWidth) + "  " + pad(rule.kind.label(), kindWidth)
                    + "  " + pad(rule.tool, toolWidth) + "  " + rule.arguments);
        }
    }

    private static void remember(SessionRuntime session, String remainder, PrintStream out) {
        String body = remainder.strip();
        String[] parts = body.split("\\s+", 3);
        if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isBlank()) {
            out.println(REMEMBER_USAGE);
            return;
        }
        SessionRules.Kind kind = SessionRules.Kind.parse(parts[0]);
        String tool = parts[1];
        if (kind == null || !SessionRules.validTool(tool)) {
            out.println(REMEMBER_USAGE);
            return;
        }
        JsonNode parsed = SessionRules.parseArguments(JSON, parts[2]);
        if (parsed == null) {
            out.println(REMEMBER_USAGE);
            return;
        }
        String key = SessionRules.normalizeArguments(parsed);
        if (!SessionRules.validIdentity(tool, key)) {
            out.println("Arguments exceed the " + SessionRules.MAX_IDENTITY_BYTES
                    + " byte combined rule identity limit.");
            return;
        }
        if (!session.persistent()) {
            out.println("Session persistence is disabled by --no-save.");
            return;
        }
        try {
            String id = session.rememberRule(kind, tool, key);
            if (id == null) {
                out.println("Rule limit reached (" + SessionRules.MAX_RULES
                        + "); revoke a rule before remembering another.");
                return;
            }
            out.println("Remembered " + kind.label() + " rule " + id + " for "
                    + tool + " " + key + " (exact match, this saved session).");
        } catch (IOException persistenceFailure) {
            out.println("Could not persist permission rule: " + persistenceFailure.getMessage());
        }
    }

    private static void revoke(SessionRuntime session, String remainder, PrintStream out) {
        String id = remainder.strip();
        if (id.isEmpty() || !id.matches("[0-9]+")) {
            out.println(REVOKE_USAGE);
            return;
        }
        if (!session.persistent()) {
            out.println("Session persistence is disabled by --no-save.");
            return;
        }
        try {
            if (!session.revokeRule(id)) {
                out.println("No rule with id " + id + ". Run /permissions to list rule ids.");
                return;
            }
            out.println("Revoked rule " + id + ".");
        } catch (IOException persistenceFailure) {
            out.println("Could not persist permission rule: " + persistenceFailure.getMessage());
        }
    }

    private static String pad(String value, int width) {
        StringBuilder out = new StringBuilder(value);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    private static PermissionMode permissionMode(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "ask": return PermissionMode.ASK;
            case "auto": return PermissionMode.AUTO;
            case "yolo": return PermissionMode.YOLO;
            default: return null;
        }
    }
}
