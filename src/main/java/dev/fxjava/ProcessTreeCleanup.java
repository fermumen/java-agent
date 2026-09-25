package dev.fxjava;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Tracks and stops descendants of captured commands with bounded waits. */
final class ProcessTreeCleanup implements AutoCloseable {
    private static final long SAMPLE_MILLIS = 10;

    private final Process process;
    private final Set<ProcessHandle> known = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Thread sampler;
    private volatile boolean closed;

    ProcessTreeCleanup(Process process, String threadName) {
        this.process = process;
        this.sampler = new Thread(() -> {
            while (!closed && process.isAlive()) {
                capture();
                try {
                    Thread.sleep(SAMPLE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            capture();
        }, threadName);
        sampler.setDaemon(true);
        sampler.start();
    }

    void capture() {
        if (!process.isAlive()) return;
        try {
            process.toHandle().descendants().forEach(known::add);
        } catch (RuntimeException ignored) {
            // ProcessHandle enumeration can race with process exit.
        }
    }

    boolean hasLiveDescendants() {
        synchronized (known) {
            for (ProcessHandle child : known) if (child.isAlive()) return true;
        }
        return false;
    }

    /**
     * Gracefully requests termination, then force-kills any remaining tracked
     * process tree. Both phases have fixed upper bounds even when descendants
     * ignore signals or retain inherited output pipes.
     */
    void terminate(boolean force, long graceMillis, long forceMillis) {
        boolean wasInterrupted = Thread.interrupted();
        capture();
        try {
            if (force) destroyTree(true);
            else destroyTree(false);
            if (!force) {
                wasInterrupted |= awaitTree(graceMillis, false);
                if (treeAlive()) destroyTree(true);
            }
            wasInterrupted |= awaitTree(forceMillis, true);
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean awaitTree(long maximumMillis, boolean force) {
        boolean wasInterrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, maximumMillis));
        while (treeAlive() && System.nanoTime() < deadline) {
            capture();
            destroyTree(force);
            try {
                Thread.sleep(Math.min(25, Math.max(1, maximumMillis)));
            } catch (InterruptedException signal) {
                // Cleanup must finish even after the initiating worker was interrupted.
                Thread.interrupted();
                wasInterrupted = true;
            }
        }
        capture();
        if (treeAlive()) destroyTree(true);
        return wasInterrupted;
    }

    private boolean treeAlive() {
        if (process.isAlive()) return true;
        return hasLiveDescendants();
    }

    private void destroyTree(boolean force) {
        capture();
        List<ProcessHandle> descendants;
        synchronized (known) { descendants = new ArrayList<>(known); }
        // Kill children before the shell so it cannot keep producing more work.
        for (ProcessHandle child : descendants) destroy(child, force);
        destroy(process.toHandle(), force);
    }

    private static void destroy(ProcessHandle handle, boolean force) {
        if (!handle.isAlive()) return;
        try {
            if (force) handle.destroyForcibly(); else handle.destroy();
        } catch (RuntimeException ignored) {
            // The process can exit between isAlive and destroy.
        }
    }

    @Override
    public void close() {
        boolean wasInterrupted = Thread.interrupted();
        closed = true;
        sampler.interrupt();
        try {
            sampler.join(250);
        } catch (InterruptedException interrupted) {
            wasInterrupted = true;
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }
}
