package dev.fxjava;

import com.sun.jna.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows console raw mode: mode bits, exact restore, fallbacks, and the key pump. */
class WindowsConsoleTest {
    /** Records mode changes and serves scripted reads; a null read ends the session. */
    private static final class FakeConsole implements WindowsConsole.Api {
        Integer input = 0x01f7;
        Integer output = 0x0003;
        boolean inputSettable = true;
        final List<String> calls = new ArrayList<>();
        final LinkedBlockingQueue<String> reads = new LinkedBlockingQueue<>();

        @Override public Integer inputMode() { return input; }
        @Override public Integer outputMode() { return output; }

        @Override public boolean setInputMode(int mode) {
            calls.add("in " + Integer.toHexString(mode));
            return inputSettable;
        }

        @Override public boolean setOutputMode(int mode) {
            calls.add("out " + Integer.toHexString(mode));
            return true;
        }

        @Override public TerminalCapabilities.Size size() { return new TerminalCapabilities.Size(30, 120); }

        @Override public String read() {
            try {
                String next = reads.take();
                return next.equals("<eof>") ? null : next;
            } catch (InterruptedException interrupted) {
                return null;
            }
        }
    }

    @Test
    void rawInputDropsLineEchoAndSignalsAndAddsVtInput() {
        int raw = WindowsConsole.rawInputMode(0x01f7);
        assertEquals(0, raw & (WindowsConsole.ENABLE_LINE_INPUT | WindowsConsole.ENABLE_ECHO_INPUT
                | WindowsConsole.ENABLE_PROCESSED_INPUT));
        assertEquals(WindowsConsole.ENABLE_VIRTUAL_TERMINAL_INPUT, raw & WindowsConsole.ENABLE_VIRTUAL_TERMINAL_INPUT);
        assertEquals(0x01f7 & 0xf0, raw & 0xf0, "unrelated flags survive");
        assertEquals(0x0007, WindowsConsole.vtOutputMode(0x0002));
    }

    @Test
    void openSwitchesOutputThenInputAndCloseRestoresBothExactly() throws Exception {
        FakeConsole console = new FakeConsole();
        RawTerminal terminal = WindowsConsole.open(console);
        assertNotNull(terminal);
        assertEquals(List.of("out 7", "in " + Integer.toHexString(WindowsConsole.rawInputMode(0x01f7))),
                console.calls);
        assertEquals(new TerminalCapabilities.Size(30, 120), terminal.size());
        console.calls.clear();
        terminal.close();
        assertEquals(List.of("in 1f7", "out 3"), console.calls);
    }

    @Test
    void redirectedHandlesFallBackWithoutTouchingModes() {
        FakeConsole console = new FakeConsole();
        console.input = null;
        assertNull(WindowsConsole.open(console));
        assertTrue(console.calls.isEmpty());
    }

    @Test
    void inputModeFailureRestoresTheOutputMode() {
        FakeConsole console = new FakeConsole();
        console.inputSettable = false;
        assertNull(WindowsConsole.open(console));
        assertEquals("out 3", console.calls.get(console.calls.size() - 1));
    }

    @Test
    void typedTextArrivesAsUtf8WithSplitSurrogatesJoined() throws Exception {
        FakeConsole console = new FakeConsole();
        RawTerminal terminal = WindowsConsole.open(console);
        InputStream keys = terminal.input(InputStream.nullInputStream());
        String emoji = "😀";
        console.reads.add("é\u001b[A" + emoji.charAt(0));
        console.reads.add(emoji.substring(1) + "\r");
        byte[] expected = ("é\u001b[A" + emoji + "\r").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, readExactly(keys, expected.length));
        console.reads.add("<eof>");
        assertEquals(-1, keys.read(), "a failed console read ends the stream");
        terminal.close();
    }

    @Test
    void standardInputIsUsedWhenTheBackendHasNoStream() {
        InputStream stdin = InputStream.nullInputStream();
        RawTerminal terminal = RawTerminal.of(() -> { }, () -> TerminalCapabilities.Size.UNKNOWN, null);
        assertTrue(terminal.input(stdin) == stdin);
        terminal.close();
    }

    @Test
    void rawTerminalOptOutAcceptsTheLauncherSpellings() {
        assertTrue(Main.rawTerminalEnabled(Map.of()));
        assertTrue(Main.rawTerminalEnabled(Map.of("JAVA_AGENT_RAW_TERMINAL", "1")));
        for (String off : List.of("0", "false", "OFF", " off ")) {
            assertFalse(Main.rawTerminalEnabled(Map.of("JAVA_AGENT_RAW_TERMINAL", off)), off);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void kernel32BindsThroughJnaAndOpenNeverThrows() {
        WindowsConsole.Kernel32 kernel = Native.load("kernel32", WindowsConsole.Kernel32.class);
        assertNotNull(kernel.GetStdHandle(-11));
        // CI has no interactive console: open() must decline cleanly, never throw.
        RawTerminal terminal = WindowsConsole.open();
        if (terminal != null) terminal.close();
    }

    private static byte[] readExactly(InputStream in, int count) throws Exception {
        byte[] out = new byte[count];
        int filled = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (filled < count && System.nanoTime() < deadline) {
            int read = in.read(out, filled, count - filled);
            if (read < 0) break;
            filled += read;
        }
        return out;
    }
}
