package dev.fxjava;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;

/**
 * Raw mode for the Windows console (Windows Terminal, conhost) through the
 * console API, since Windows has no {@code stty}: line input, echo, and
 * Ctrl+C signal processing turn off, VT input and output turn on, and both
 * saved modes restore exactly on close. Keys come from {@code ReadConsoleW}
 * on a daemon thread into a {@link QueuedInputStream}, because the JDK's
 * console {@code available()} reports nothing until Enter is pressed, and
 * UTF-16 reads stay correct whatever the console code page is.
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
        /** Current input mode, or null when stdin is not a console. */
        Integer inputMode();

        /** Current output mode, or null when stdout is not a console. */
        Integer outputMode();

        boolean setInputMode(int mode);

        boolean setOutputMode(int mode);

        TerminalCapabilities.Size size();

        /** Blocks for the next typed UTF-16 text; null when the read fails. */
        String read();
    }

    private WindowsConsole() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /** Enters raw mode on the attached console, or null when unsupported or JNA cannot load. */
    static RawTerminal open() {
        try {
            return open(new Kernel32Api());
        } catch (LinkageError | RuntimeException unavailable) {
            // Missing native support (or a locked-down temp directory JNA
            // cannot unpack into) falls back to the line-mode shell.
            return null;
        }
    }

    static RawTerminal open(Api api) {
        Integer savedInput = api.inputMode();
        Integer savedOutput = api.outputMode();
        if (savedInput == null || savedOutput == null) return null;
        if (!api.setOutputMode(vtOutputMode(savedOutput))) return null;
        if (!api.setInputMode(rawInputMode(savedInput))) {
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

    /**
     * Copies console text into the queue as UTF-8 until a read fails or the
     * queue closes; a high surrogate ending one read waits for its pair.
     */
    static void pump(Api api, QueuedInputStream keys) {
        String carry = "";
        while (!keys.closed()) {
            String chunk = api.read();
            if (chunk == null) {
                keys.close();
                return;
            }
            String text = carry + chunk;
            carry = "";
            if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                carry = text.substring(text.length() - 1);
                text = text.substring(0, text.length() - 1);
            }
            if (!text.isEmpty()) keys.offer(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer GetStdHandle(int which);

        boolean GetConsoleMode(Pointer handle, IntByReference mode);

        boolean SetConsoleMode(Pointer handle, int mode);

        boolean GetConsoleScreenBufferInfo(Pointer handle, Pointer info);

        boolean ReadConsoleW(Pointer handle, Pointer buffer, int toRead, IntByReference read, Pointer control);
    }

    private static final class Kernel32Api implements Api {
        private static final int STD_INPUT_HANDLE = -10;
        private static final int STD_OUTPUT_HANDLE = -11;
        /** CONSOLE_SCREEN_BUFFER_INFO: srWindow's Left/Top/Right/Bottom shorts start at byte 10. */
        private static final int SCREEN_BUFFER_INFO_BYTES = 22;
        private static final int READ_CHARS = 256;

        private final Kernel32 kernel = Native.load("kernel32", Kernel32.class);
        private final Pointer input = kernel.GetStdHandle(STD_INPUT_HANDLE);
        private final Pointer output = kernel.GetStdHandle(STD_OUTPUT_HANDLE);
        private final Memory readBuffer = new Memory(READ_CHARS * 2L);

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
        public String read() {
            IntByReference count = new IntByReference();
            if (!kernel.ReadConsoleW(input, readBuffer, READ_CHARS, count, null)) return null;
            return new String(readBuffer.getCharArray(0, count.getValue()));
        }
    }
}
