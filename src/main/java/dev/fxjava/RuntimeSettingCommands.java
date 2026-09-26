package dev.fxjava;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;

/** Shared /model and /effort handlers for both interactive shell modes. */
final class RuntimeSettingCommands {
    private static final String MODEL_USAGE = "Usage: /model [<id> [--save]]";

    static String model(SessionRuntime session, String arguments, Path settingsRoot, String source,
                        PrintStream out, PrintStream error) {
        Selection selection = parse(arguments);
        if (selection.invalid) {
            out.println(MODEL_USAGE);
            return source;
        }
        if (selection.value == null) {
            out.println("Model " + session.model() + " · source: " + source);
            out.println("Usage: /model <id> [--save]");
            return source;
        }
        if (selection.value.length() > 256 || selection.value.codePoints().anyMatch(Character::isISOControl)) {
            out.println(MODEL_USAGE);
            return source;
        }
        try {
            session.setModel(selection.value);
        } catch (IllegalArgumentException invalid) {
            out.println(MODEL_USAGE);
            return source;
        }
        boolean saved = selection.save && saveModel(settingsRoot, selection.value, out, error);
        out.println("Model set for this session: " + session.model());
        return saved ? "saved preference" : "session override";
    }

    static String effort(SessionRuntime session, String arguments, Path settingsRoot, String source,
                         PrintStream out, PrintStream error) {
        Selection selection = parse(arguments);
        if (selection.invalid) {
            out.println(effortUsage());
            return source;
        }
        if (selection.value == null) {
            String value = session.reasoningEffort();
            out.println("Reasoning effort " + (value == null ? "provider default" : value)
                    + " · source: " + source);
            out.println(effortUsage());
            return source;
        }
        String value = selection.value.toLowerCase(Locale.ROOT);
        if (!value.equals("default") && !AgentConfig.reasoningEffortValues().contains(value)) {
            out.println(effortUsage());
            return source;
        }
        try {
            session.setReasoningEffort(value.equals("default") ? null : value);
        } catch (IllegalArgumentException invalid) {
            out.println(effortUsage());
            return source;
        }
        boolean saved = selection.save
                && saveEffort(settingsRoot, value.equals("default") ? null : value, out, error);
        String active = session.reasoningEffort();
        out.println("Reasoning effort set for this session: "
                + (active == null ? "provider default" : active));
        return saved ? "saved preference" : "session override";
    }

    static String effortUsage() {
        Collection<String> values = AgentConfig.reasoningEffortValues();
        return "Usage: /effort [" + String.join("|", values) + "|default] [--save]";
    }

    private static Selection parse(String arguments) {
        String text = arguments == null ? "" : arguments.strip();
        if (text.isEmpty()) return new Selection(null, false, false);
        String[] parts = text.split("\\s+");
        boolean save = parts[parts.length - 1].equals("--save");
        int count = save ? parts.length - 1 : parts.length;
        if (count != 1) return new Selection(null, false, true);
        return new Selection(parts[0], save, false);
    }

    private static boolean saveModel(Path root, String model, PrintStream out, PrintStream error) {
        try {
            UserPreferences.load(root).withModel(model).save();
            out.println("Saved model preference. CLI flags and environment variables take precedence next run.");
            return true;
        } catch (IOException failure) {
            error.println("java-agent: secure settings could not be saved; model remains set for this session only.");
            return false;
        }
    }

    private static boolean saveEffort(Path root, String effort, PrintStream out, PrintStream error) {
        try {
            UserPreferences.load(root).withReasoningEffort(effort).save();
            out.println("Saved reasoning-effort preference. CLI flags and environment variables take precedence next run.");
            return true;
        } catch (IOException failure) {
            error.println("java-agent: secure settings could not be saved; effort remains set for this session only.");
            return false;
        }
    }

    private static final class Selection {
        final String value;
        final boolean save;
        final boolean invalid;

        Selection(String value, boolean save, boolean invalid) {
            this.value = value;
            this.save = save;
            this.invalid = invalid;
        }
    }

    private RuntimeSettingCommands() { }
}
