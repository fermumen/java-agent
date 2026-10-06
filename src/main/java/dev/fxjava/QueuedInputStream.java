package dev.fxjava;

import java.io.InputStream;

/**
 * Byte queue fed by a reader thread. {@link #available()} reports exactly what
 * is buffered, which the shell's generation loop relies on to poll for keys
 * without blocking; reads block until bytes arrive or the queue closes (EOF).
 */
final class QueuedInputStream extends InputStream {
    private byte[] buffer = new byte[256];
    private int head;
    private int tail;
    private boolean closed;

    synchronized void offer(byte[] bytes) {
        if (closed || bytes.length == 0) return;
        if (tail + bytes.length > buffer.length) {
            int size = tail - head;
            byte[] grown = size + bytes.length > buffer.length
                    ? new byte[Math.max(buffer.length * 2, size + bytes.length)] : buffer;
            System.arraycopy(buffer, head, grown, 0, size);
            buffer = grown;
            head = 0;
            tail = size;
        }
        System.arraycopy(bytes, 0, buffer, tail, bytes.length);
        tail += bytes.length;
        notifyAll();
    }

    synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized int available() {
        return tail - head;
    }

    @Override
    public synchronized int read() {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public synchronized int read(byte[] target, int offset, int length) {
        return read(target, offset, length, 0);
    }

    /** Zero on timeout, EOF only on close; lets the Windows UI poll resize while idle. */
    synchronized int read(byte[] target, int offset, int length, long timeoutMillis) {
        if (length == 0) return 0;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (head == tail && !closed) {
            try {
                if (timeoutMillis == 0) wait();
                else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return 0;
                    wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        if (head == tail) return -1;
        int count = Math.min(length, tail - head);
        System.arraycopy(buffer, head, target, offset, count);
        head += count;
        if (head == tail) {
            head = 0;
            tail = 0;
        }
        return count;
    }

    /** Ends the stream: buffered bytes still drain, then reads return EOF. */
    @Override
    public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
