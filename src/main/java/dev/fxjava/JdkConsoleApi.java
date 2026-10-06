package dev.fxjava;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Console API binding through the JDK's own {@code jdk.internal.le} natives
 * (JLine's {@code Kernel32Impl}, backed by {@code le.dll} in the JDK's bin
 * directory), so no DLL is unpacked anywhere. The package is not exported:
 * the jar manifest opens it with {@code Add-Opens}. Present from JDK
 * 11.0.14 through 21; {@link #create()} throws on JDKs without it. Only
 * jshell requires the module, so a JRE never resolves it by default: the
 * Windows launcher adds {@code --add-modules jdk.internal.le} when the
 * runtime's {@code release} file lists it.
 *
 * <p>Keys are read as raw bytes from standard input. With line input off the
 * console returns them as typed, already UTF-8 under the launcher's UTF-8
 * input code page; this avoids JLine's event records, whose native fill code
 * writes {@code int} values into {@code char} fields.
 */
final class JdkConsoleApi implements WindowsConsole.Api {
    private static final String MODULE = "jdk.internal.le";
    private static final String PACKAGE = "jdk.internal.org.jline.terminal.impl.jna.win.";
    private static final int STD_INPUT_HANDLE = -10;
    private static final int STD_OUTPUT_HANDLE = -11;

    private final Object kernel;
    private final Object input;
    private final Object output;
    private final Method getConsoleMode;
    private final Method setConsoleMode;
    private final Method getScreenBufferInfo;
    private final Constructor<?> newIntReference;
    private final Field intReferenceValue;
    private final Constructor<?> newScreenBufferInfo;
    private final Field window;
    private final Field left;
    private final Field top;
    private final Field right;
    private final Field bottom;
    private final FileInputStream stdin = new FileInputStream(FileDescriptor.in);
    private final byte[] readBuffer = new byte[1024];
    private volatile String lastError = "none";

    private JdkConsoleApi() throws ReflectiveOperationException {
        Class<?> impl = Class.forName(PACKAGE + "Kernel32Impl");
        Class<?> pointer = Class.forName(PACKAGE + "Pointer");
        Class<?> intReference = Class.forName(PACKAGE + "IntByReference");
        Class<?> screenBufferInfo = Class.forName(PACKAGE + "Kernel32$CONSOLE_SCREEN_BUFFER_INFO");
        Class<?> smallRect = Class.forName(PACKAGE + "Kernel32$SMALL_RECT");
        kernel = accessible(impl.getDeclaredConstructor()).newInstance();
        Method getStdHandle = accessible(impl.getMethod("GetStdHandle", int.class));
        input = getStdHandle.invoke(kernel, STD_INPUT_HANDLE);
        output = getStdHandle.invoke(kernel, STD_OUTPUT_HANDLE);
        getConsoleMode = accessible(impl.getMethod("GetConsoleMode", pointer, intReference));
        setConsoleMode = accessible(impl.getMethod("SetConsoleMode", pointer, int.class));
        getScreenBufferInfo = accessible(impl.getMethod("GetConsoleScreenBufferInfo", pointer, screenBufferInfo));
        newIntReference = accessible(intReference.getDeclaredConstructor());
        intReferenceValue = accessible(intReference.getDeclaredField("value"));
        newScreenBufferInfo = accessible(screenBufferInfo.getDeclaredConstructor());
        window = accessible(screenBufferInfo.getDeclaredField("srWindow"));
        left = accessible(smallRect.getDeclaredField("Left"));
        top = accessible(smallRect.getDeclaredField("Top"));
        right = accessible(smallRect.getDeclaredField("Right"));
        bottom = accessible(smallRect.getDeclaredField("Bottom"));
    }

    /** Binds the JDK natives; throws when the JDK lacks them or the package is not opened. */
    static JdkConsoleApi create() throws ReflectiveOperationException {
        if (ModuleLayer.boot().findModule(MODULE).isEmpty()) {
            // A JRE without jshell ships the module but never resolves it.
            throw new ClassNotFoundException(ModuleFinder.ofSystem().find(MODULE).isPresent()
                    ? MODULE + " is in this runtime but not loaded; start Java with --add-modules "
                        + MODULE + " (java-agent.cmd does this)"
                    : MODULE + " is not in this runtime");
        }
        return new JdkConsoleApi();
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }

    @Override
    public String name() {
        return "JDK jdk.internal.le";
    }

    @Override
    public Integer inputMode() {
        return mode(input);
    }

    @Override
    public Integer outputMode() {
        return mode(output);
    }

    private Integer mode(Object handle) {
        try {
            Object mode = newIntReference.newInstance();
            getConsoleMode.invoke(kernel, handle, mode);
            return intReferenceValue.getInt(mode);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            lastError = describe(failed);
            return null;
        }
    }

    @Override
    public boolean setInputMode(int mode) {
        return setMode(input, mode);
    }

    @Override
    public boolean setOutputMode(int mode) {
        return setMode(output, mode);
    }

    private boolean setMode(Object handle, int mode) {
        try {
            setConsoleMode.invoke(kernel, handle, mode);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            lastError = describe(failed);
            return false;
        }
    }

    @Override
    public TerminalCapabilities.Size size() {
        try {
            Object info = newScreenBufferInfo.newInstance();
            getScreenBufferInfo.invoke(kernel, output, info);
            Object rect = window.get(info);
            int columns = right.getShort(rect) - left.getShort(rect) + 1;
            int rows = bottom.getShort(rect) - top.getShort(rect) + 1;
            return rows > 0 && columns > 0
                    ? new TerminalCapabilities.Size(rows, columns) : TerminalCapabilities.Size.UNKNOWN;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            lastError = describe(failed);
            return TerminalCapabilities.Size.UNKNOWN;
        }
    }

    @Override
    public byte[] read() {
        try {
            int count = stdin.read(readBuffer);
            return count < 0 ? null : Arrays.copyOf(readBuffer, count);
        } catch (IOException failed) {
            lastError = failed.toString();
            return null;
        }
    }

    @Override
    public String lastError() {
        return lastError;
    }

    /** JLine's natives throw LastErrorException carrying the Win32 error code. */
    private static String describe(Throwable failed) {
        Throwable cause = failed instanceof InvocationTargetException && failed.getCause() != null
                ? failed.getCause() : failed;
        return cause.toString();
    }
}
