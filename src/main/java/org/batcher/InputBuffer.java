package org.batcher;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared lifecycle and scheduling; element storage is specialized by generated input type. */
abstract class InputBuffer<T> implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(InputBuffer.class);

    protected final BatchSettings settings;
    protected int size;
    private final Consumer<? super List<T>> handler;
    private final ScheduledThreadPoolExecutor timer;
    private final ExecutorService worker;
    private volatile Thread workerThread;
    private ScheduledFuture<?> timeoutTask;
    private long generation;
    private boolean closed;

    InputBuffer(BatchSettings settings, Consumer<? super List<T>> handler) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.handler = Objects.requireNonNull(handler, "handler");
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "batch-input-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "batch-input-worker");
            thread.setDaemon(true);
            workerThread = thread;
            return thread;
        });
    }

    // Called while the specialized add method holds this buffer's monitor.
    protected final void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Batch input has already been closed");
        }
    }

    protected final void elementAdded() {
        size++;
        if (size == settings.maxSize()) {
            flush();
        } else if (timeoutTask == null) {
            long expectedGeneration = ++generation;
            timeoutTask = timer.schedule(() -> onTimeout(expectedGeneration),
                    settings.timeout().toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private synchronized void onTimeout(long expectedGeneration) {
        // A cancelled timer may already be waiting for the monitor; do not flush the next batch.
        if (!closed && generation == expectedGeneration) {
            flush();
        }
    }

    protected abstract Supplier<List<T>> detach();

    private void flush() {
        generation++;
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        if (size == 0) {
            return;
        }
        int count = size;
        Supplier<List<T>> batch = detach();
        size = 0;
        worker.execute(() -> {
            try {
                handler.accept(batch.get());
            } catch (Throwable exception) {
                logger.error("Asynchronous batch input delivery failed ({} elements)", count, exception);
            }
        });
    }

    @Override
    public final void close() {
        synchronized (this) {
            if (!closed) {
                closed = true;
                flush();
                timer.shutdownNow();
                worker.shutdown();
            }
        }
        // A handler can close its input, but cannot wait for its own worker to terminate.
        if (Thread.currentThread() == workerThread) {
            return;
        }
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Batch deliveries did not finish within 30 seconds; queued deliveries are still running");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for batch deliveries", exception);
        }
    }
}
