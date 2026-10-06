package dev.fxjava;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Existing scripted CLI tests now paste commands into the real raw-shell entrypoint. */
final class TestRawShell {
    private TestRawShell() { }

    static int run(String[] args, Map<String, String> environment, InputStream script,
                   PrintStream out, PrintStream error) throws Exception {
        return run(args, environment, script, out, error, null);
    }

    static int run(String[] args, Map<String, String> environment, InputStream script,
                   PrintStream out, PrintStream error, ApprovalRouter approval) throws Exception {
        StringBuilder keys = new StringBuilder();
        for (String line : new String(script.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            keys.append("\u001b[200~").append(line).append("\u001b[201~\r");
        }
        InputStream input = new ByteArrayInputStream(keys.toString().getBytes(StandardCharsets.UTF_8));
        RawTerminal terminal = RawTerminal.of(() -> { }, () -> new TerminalCapabilities.Size(24, 100), input);
        return Main.run(args, environment, input, out, error, approval, terminal);
    }
}
