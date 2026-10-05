package dev.fxjava;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/** Stand-in for the productivity bundle's BshRun main class in BeanShellTool tests. */
public final class FakeBshRunner {
    private FakeBshRunner() { }

    public static void main(String[] args) throws Exception {
        System.out.println("script=" + args[0]);
        System.out.println("args=" + Arrays.toString(Arrays.copyOfRange(args, 1, args.length)));
        System.out.println("cwd=" + Path.of("").toAbsolutePath().getFileName());
        System.out.println("headless=" + System.getProperty("java.awt.headless"));
        System.out.println("api-key=" + System.getenv("OPENAI_API_KEY"));
        if (args[0].equals("-")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = System.in;
            in.transferTo(buffer);
            String script = buffer.toString(StandardCharsets.UTF_8);
            System.out.println("stdin=" + script);
            if (script.contains("fail")) System.exit(3);
        }
    }
}
