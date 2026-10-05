package dev.fxjava;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** available() reports exactly what is buffered; reads block, drain, then hit EOF on close. */
class QueuedInputStreamTest {
    @Test
    void availableTracksBufferedBytesAcrossGrowth() {
        QueuedInputStream queue = new QueuedInputStream();
        assertEquals(0, queue.available());
        queue.offer(new byte[300]);
        queue.offer(new byte[]{1, 2, 3});
        assertEquals(303, queue.available());
        byte[] sink = new byte[302];
        assertEquals(302, queue.read(sink, 0, sink.length));
        assertEquals(1, queue.available());
        assertEquals(3, queue.read());
        assertEquals(0, queue.available());
    }

    @Test
    void readBlocksUntilBytesArrive() throws Exception {
        QueuedInputStream queue = new QueuedInputStream();
        CompletableFuture<Integer> pending = CompletableFuture.supplyAsync(queue::read);
        Thread.sleep(50);
        assertFalse(pending.isDone());
        queue.offer(new byte[]{42});
        assertEquals(42, pending.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeDrainsBufferedBytesThenReturnsEof() throws Exception {
        QueuedInputStream queue = new QueuedInputStream();
        queue.offer(new byte[]{7});
        queue.close();
        queue.offer(new byte[]{8});
        assertEquals(7, queue.read());
        assertEquals(-1, queue.read());
        CompletableFuture<Integer> blocked = new CompletableFuture<>();
        QueuedInputStream waiting = new QueuedInputStream();
        Thread reader = new Thread(() -> blocked.complete(waiting.read()));
        reader.start();
        Thread.sleep(50);
        waiting.close();
        assertEquals(-1, blocked.get(5, TimeUnit.SECONDS));
    }
}
