package dev.fxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded type-ahead buffering for keystrokes typed during generation. */
class TypeAheadQueueTest {
    @Test
    void offersAccumulateAndDrainClears() {
        TypeAheadQueue queue = new TypeAheadQueue(64);
        queue.offer("fix ");
        queue.offer("the ");
        queue.offer("bug");
        assertEquals(11, queue.length());
        assertEquals("fix the bug", queue.drain());
        assertEquals(0, queue.length());
        assertEquals("", queue.drain());
    }

    @Test
    void overflowChunksAreDroppedSilently() {
        TypeAheadQueue queue = new TypeAheadQueue(8);
        queue.offer("12345");
        queue.offer("6789");
        assertEquals("12345", queue.drain(), "chunk that would exceed the cap is dropped whole");
        queue.offer(null);
        queue.offer("");
        assertEquals("", queue.drain());
    }

    @Test
    void capacityBoundaryIsExact() {
        TypeAheadQueue queue = new TypeAheadQueue(10);
        queue.offer("0123456789");
        assertTrue(queue.length() == 10);
        queue.offer("+");
        assertEquals(10, queue.length());
    }
}
