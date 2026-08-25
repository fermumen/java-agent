package dev.fxjava;

/**
 * Bounded type-ahead buffer for keystrokes typed while a turn generates: text
 * chunks are held (whole-chunk drops past the cap) and replayed into the
 * composer once the turn ends.
 */
final class TypeAheadQueue {
    private final StringBuilder buffer = new StringBuilder();
    private final int capacity;

    TypeAheadQueue(int capacityChars) {
        this.capacity = Math.max(0, capacityChars);
    }

    /** Offers one chunk; silently dropped when it would exceed the capacity. */
    synchronized void offer(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (buffer.length() + chunk.length() > capacity) return;
        buffer.append(chunk);
    }

    synchronized String drain() {
        String value = buffer.toString();
        buffer.setLength(0);
        return value;
    }

    synchronized int length() {
        return buffer.length();
    }
}
