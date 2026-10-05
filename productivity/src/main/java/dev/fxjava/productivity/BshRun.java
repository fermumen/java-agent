package dev.fxjava.productivity;

import bsh.EvalError;
import bsh.Interpreter;
import bsh.TargetError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Fail-closed BeanShell script runner.
 *
 * <p>{@code bsh.Interpreter.main} prints script failures and still exits 0, so a printed success
 * line cannot be trusted. This runner exits nonzero for parse errors, evaluation errors, and
 * uncaught script exceptions, and exits 0 only after the whole script completed.
 *
 * <pre>java -jar productivity.jar script.bsh [args...]</pre>
 *
 * Use {@code -} as the script path to read the script from standard input. Script arguments are
 * available to the script as {@code bsh.args}. Scripts are read as UTF-8 with an optional BOM.
 */
public final class BshRun {
    static final int SCRIPT_FAILED = 1;
    static final int USAGE = 64;

    private BshRun() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.err.println("usage: java -jar productivity.jar <script.bsh | -> [args...]");
            return USAGE;
        }
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");

        String script = args[0];
        String[] scriptArgs = Arrays.copyOfRange(args, 1, args.length);
        Interpreter interpreter = new Interpreter();
        try (Reader reader = open(script)) {
            interpreter.set("bsh.args", scriptArgs);
            interpreter.eval(reader, interpreter.getNameSpace(), script.equals("-") ? "<stdin>" : script);
            return 0;
        } catch (TargetError failure) {
            System.err.println("BeanShell script threw an exception at " + location(failure) + ":");
            failure.getTarget().printStackTrace();
            return SCRIPT_FAILED;
        } catch (EvalError failure) {
            System.err.println("BeanShell evaluation error at " + location(failure) + ": " + String.valueOf(failure.getMessage()).trim());
            return SCRIPT_FAILED;
        } catch (NoSuchFileException missing) {
            System.err.println("BeanShell script not found: " + missing.getFile());
            return SCRIPT_FAILED;
        } catch (IOException failure) {
            System.err.println("BeanShell script could not be read: " + failure);
            return SCRIPT_FAILED;
        } catch (RuntimeException | Error failure) {
            System.err.println("BeanShell interpreter failed:");
            failure.printStackTrace();
            return SCRIPT_FAILED;
        } finally {
            System.out.flush();
            System.err.flush();
        }
    }

    private static Reader open(String script) throws IOException {
        Reader raw = script.equals("-")
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8)
                : Files.newBufferedReader(Path.of(script), StandardCharsets.UTF_8);
        PushbackReader reader = new PushbackReader(new BufferedReader(raw), 1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') reader.unread(first);
        return reader;
    }

    private static String location(EvalError failure) {
        String file;
        int line;
        try {
            file = failure.getErrorSourceFile();
            line = failure.getErrorLineNumber();
        } catch (RuntimeException unavailable) {
            return "<unknown>";
        }
        if (file == null) file = "<unknown>";
        return line > 0 ? file + ":" + line : file;
    }
}
