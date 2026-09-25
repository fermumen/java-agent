package dev.fxjava;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** UTF-8 line reader with fixed byte and per-line memory bounds. */
final class BoundedTextReader implements AutoCloseable {
    static final class Line {
        private final String text;
        private final boolean truncated;
        private final boolean partial;
        private final boolean containsNul;
        private final boolean terminated;

        Line(String text, boolean truncated, boolean partial, boolean containsNul, boolean terminated) {
            this.text = text;
            this.truncated = truncated;
            this.partial = partial;
            this.containsNul = containsNul;
            this.terminated = terminated;
        }

        String text() { return text; }
        boolean truncated() { return truncated; }
        boolean partial() { return partial; }
        boolean containsNul() { return containsNul; }
        boolean terminated() { return terminated; }
    }

    private final PushbackReader reader;
    private final LimitedInputStream input;
    private final int maxLineChars;

    private BoundedTextReader(PushbackReader reader, LimitedInputStream input, int maxLineChars) {
        this.reader = reader;
        this.input = input;
        this.maxLineChars = maxLineChars;
    }

    static BoundedTextReader open(Path path, long maxBytes, int maxLineChars) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        if (maxLineChars < 1) throw new IllegalArgumentException("maxLineChars must be positive");
        long size = Files.size(path);
        LimitedInputStream input = new LimitedInputStream(
                new BufferedInputStream(Files.newInputStream(path)), maxBytes, size > maxBytes);
        InputStreamReader decoded = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        return new BoundedTextReader(new PushbackReader(new BufferedReader(decoded), 1), input, maxLineChars);
    }

    /** Reads one logical line, retaining at most {@code maxLineChars}. */
    Line readLine() throws IOException {
        StringBuilder retained = new StringBuilder(Math.min(maxLineChars, 256));
        boolean readAny = false;
        boolean truncated = false;
        boolean containsNul = false;
        int charsSinceInterruptCheck = 0;
        for (;;) {
            if ((charsSinceInterruptCheck++ & 0x1fff) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Text scan interrupted");
            }
            int value;
            try {
                value = reader.read();
            } catch (CharacterCodingException malformed) {
                if (!input.limitReached()) throw malformed;
                if (!readAny) return null;
                return new Line(retained.toString(), truncated, true, containsNul, false);
            }
            if (value < 0) {
                if (!readAny) return null;
                return new Line(retained.toString(), truncated, input.limitReached(), containsNul, false);
            }
            if (isLineBreak(value)) {
                if (value == '\r') {
                    int next = reader.read();
                    if (next >= 0 && next != '\n') reader.unread(next);
                }
                return new Line(retained.toString(), truncated, false, containsNul, true);
            }
            readAny = true;
            containsNul |= value == 0;
            if (retained.length() < maxLineChars) {
                retained.append((char) value);
            } else if (Character.isLowSurrogate((char) value) && retained.length() > 0
                    && Character.isHighSurrogate(retained.charAt(retained.length() - 1))) {
                // Keep a supplementary code point whole even at the character boundary.
                retained.append((char) value);
                truncated = true;
            } else {
                truncated = true;
            }
        }
    }

    /** True when the fixed byte allowance ended before the file did. */
    boolean byteLimitReached() {
        return input.limitReached();
    }

    long bytesRead() {
        return input.bytesRead();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private static boolean isLineBreak(int value) {
        return value == '\n' || value == '\r' || value == '\u000b' || value == '\f'
                || value == '\u0085' || value == '\u2028' || value == '\u2029';
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long byteLimit;
        private final boolean fileExceedsLimit;
        private long bytesRead;

        LimitedInputStream(InputStream delegate, long byteLimit, boolean fileExceedsLimit) {
            this.delegate = delegate;
            this.byteLimit = byteLimit;
            this.fileExceedsLimit = fileExceedsLimit;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= byteLimit) return -1;
            int value = delegate.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            long remaining = byteLimit - bytesRead;
            if (remaining <= 0) return -1;
            int count = delegate.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) bytesRead += count;
            return count;
        }

        boolean limitReached() {
            return fileExceedsLimit && bytesRead >= byteLimit;
        }

        long bytesRead() {
            return bytesRead;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
