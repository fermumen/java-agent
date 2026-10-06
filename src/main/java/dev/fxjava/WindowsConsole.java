package dev.fxjava;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Raw mode for the Windows console (Windows Terminal, conhost) through the
 * console API, since Windows has no {@code stty}: line input, echo, and
 * Ctrl+C signal processing turn off, VT input and output turn on, and both
 * saved modes restore exactly on close. Keys are read on a daemon thread into
 * a {@link QueuedInputStream}, because the JDK's console {@code available()}
 * reports nothing until Enter is pressed.
 *
 * <p>Two backends reach the console API. The JDK's own {@code jdk.internal.le}
 * bindings (what jshell uses; JDK 11.0.14+ through 21) come first because
 * their DLL ships inside the JDK. JNA is the fallback: it unpacks a DLL into
 * the temp directory, which some JVMs or endpoint policies refuse to bind.
 */
final class WindowsConsole {
    static final int ENABLE_PROCESSED_INPUT = 0x0001;
    static final int ENABLE_LINE_INPUT = 0x0002;
    static final int ENABLE_ECHO_INPUT = 0x0004;
    static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;
    static final int ENABLE_PROCESSED_OUTPUT = 0x0001;
    static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

    /** The console calls raw mode needs; the seam tests replace. */
    interface Api {
        /** Which binding this is, for doctor output. */
        String name();

        /** Current input mode, or null when stdin is not a console. */
        Integer inputMode();

        /** Current output mode, or null when stdout is not a console. */
        Integer outputMode();

        boolean setInputMode(int mode);

        boolean setOutputMode(int mode);

        TerminalCapabilities.Size size();

        /** Blocks for the next typed input as UTF-8; null when the read fails. */
        byte[] read();

        /** What the last failed call reported, for fallback diagnostics. */
        String lastError();
    }

    private WindowsConsole() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /**
     * Enters raw mode on the attached console, or returns null after telling
     * {@code declined} why: not a console, a refused mode, or no usable binding.
     */
    static RawTerminal open(Consumer<String> declined) {
        Api api = api(declined);
        return api == null ? null : open(api, declined);
    }

    /** The first binding that loads, or null after reporting why neither did. */
    static Api api(Consumer<String> declined) {
        String jdkFailure;
        try {
            return JdkConsoleApi.create();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            jdkFailure = unavailable.toString();
        }
        try {
            return new Kernel32Api(jdkFailure);
        } catch (LinkageError | RuntimeException unavailable) {
            declined.accept("no console API binding loaded (JDK jdk.internal.le: " + jdkFailure
                    + "; JNA: " + unavailable + ")");
            return null;
        }
    }

    static RawTerminal open(Api api, Consumer<String> declined) {
        Integer savedInput = api.inputMode();
        if (savedInput == null) {
            declined.accept("standard input is not a console (GetConsoleMode: " + api.lastError() + ")");
            return null;
        }
        Integer savedOutput = api.outputMode();
        if (savedOutput == null) {
            declined.accept("standard output is not a console (GetConsoleMode: " + api.lastError() + ")");
            return null;
        }
        if (!api.setOutputMode(vtOutputMode(savedOutput))) {
            declined.accept("the console refused VT output (SetConsoleMode: " + api.lastError() + ")");
            return null;
        }
        if (!api.setInputMode(rawInputMode(savedInput))) {
            declined.accept("the console refused raw VT input (SetConsoleMode: " + api.lastError() + ")");
            api.setOutputMode(savedOutput);
            return null;
        }
        QueuedInputStream keys = new QueuedInputStream();
        Thread reader = new Thread(() -> pump(api, keys), "console-input");
        reader.setDaemon(true);
        reader.start();
        return RawTerminal.of(() -> {
            keys.close();
            api.setInputMode(savedInput);
            api.setOutputMode(savedOutput);
        }, api::size, keys);
    }

    static int rawInputMode(int saved) {
        return (saved & ~(ENABLE_PROCESSED_INPUT | ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT))
                | ENABLE_VIRTUAL_TERMINAL_INPUT;
    }

    static int vtOutputMode(int saved) {
        return saved | ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING;
    }

    /** Copies console input into the queue until a read fails or the queue closes. */
    static void pump(Api api, QueuedInputStream keys) {
        while (!keys.closed()) {
            byte[] chunk = api.read();
            if (chunk == null) {
                keys.close();
                return;
            }
            keys.offer(chunk);
        }
    }

    /**
     * UTF-8 for one UTF-16 read; a high surrogate ending the read is held in
     * {@code carry} until its pair arrives with the next one.
     */
    static byte[] utf8(String chunk, StringBuilder carry) {
        String text = carry + chunk;
        carry.setLength(0);
        if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
            carry.append(text.charAt(text.length() - 1));
            text = text.substring(0, text.length() - 1);
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer GetStdHandle(int which);

        boolean GetConsoleMode(Pointer handle, IntByReference mode);

        boolean SetConsoleMode(Pointer handle, int mode);

        boolean GetConsoleScreenBufferInfo(Pointer handle, Pointer info);

        boolean ReadConsoleW(Pointer handle, Pointer buffer, int toRead, IntByReference read, Pointer control);
    }

    /** JNA binding; keys come from {@code ReadConsoleW}, so they are UTF-16 whatever the code page. */
    static final class Kernel32Api implements Api {
        private static final int STD_INPUT_HANDLE = -10;
        private static final int STD_OUTPUT_HANDLE = -11;
        /** CONSOLE_SCREEN_BUFFER_INFO: srWindow's Left/Top/Right/Bottom shorts start at byte 10. */
        private static final int SCREEN_BUFFER_INFO_BYTES = 22;
        private static final int READ_CHARS = 256;

        private final Kernel32 kernel = Native.load("kernel32", Kernel32.class);
        private final Pointer input = kernel.GetStdHandle(STD_INPUT_HANDLE);
        private final Pointer output = kernel.GetStdHandle(STD_OUTPUT_HANDLE);
        private final Memory readBuffer = new Memory(READ_CHARS * 2L);
        private final StringBuilder carry = new StringBuilder();
        private final String jdkFailure;

        /** {@code jdkFailure} says why the preferred JDK binding was skipped. */
        Kernel32Api(String jdkFailure) {
            this.jdkFailure = jdkFailure;
        }

        @Override
        public String name() {
            return "JNA (JDK jdk.internal.le skipped: " + jdkFailure + ")";
        }

        @Override
        public Integer inputMode() {
            return mode(input);
        }

        @Override
        public Integer outputMode() {
            return mode(output);
        }

        private Integer mode(Pointer handle) {
            IntByReference mode = new IntByReference();
            return kernel.GetConsoleMode(handle, mode) ? mode.getValue() : null;
        }

        @Override
        public boolean setInputMode(int mode) {
            return kernel.SetConsoleMode(input, mode);
        }

        @Override
        public boolean setOutputMode(int mode) {
            return kernel.SetConsoleMode(output, mode);
        }

        @Override
        public TerminalCapabilities.Size size() {
            Memory info = new Memory(SCREEN_BUFFER_INFO_BYTES);
            if (!kernel.GetConsoleScreenBufferInfo(output, info)) return TerminalCapabilities.Size.UNKNOWN;
            int columns = info.getShort(14) - info.getShort(10) + 1;
            int rows = info.getShort(16) - info.getShort(12) + 1;
            return rows > 0 && columns > 0
                    ? new TerminalCapabilities.Size(rows, columns) : TerminalCapabilities.Size.UNKNOWN;
        }

        @Override
        public String lastError() {
            return "error " + Native.getLastError();
        }

        @Override
        public byte[] read() {
            IntByReference count = new IntByReference();
            if (!kernel.ReadConsoleW(input, readBuffer, READ_CHARS, count, null)) return null;
            return utf8(new String(readBuffer.getCharArray(0, count.getValue())), carry);
        }
    }
}
