package org.batcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BatchExecutor {

    private static final Logger logger = LoggerFactory.getLogger(BatchExecutor.class);

    // Entries live until shutdown: evicting a buffer could discard undelivered elements.
    private final Cache<Object, Buffer> buffers = Caffeine.newBuilder().build();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "batch-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "batch-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    BatchExecutor() {
        timer.setRemoveOnCancelPolicy(true);
    }

    void enqueue(Object key, Batch settings, String description, Delivery delivery, Collection<?> input) {
        if (input == null) {
            throw new IllegalArgumentException("@Batch collection must not be null: " + description);
        }
        List<?> snapshot = new ArrayList<>(input);
        lifecycle.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("Batching has already been shut down");
            }
            if (!snapshot.isEmpty()) {
                buffers.get(key, ignored -> new Buffer(settings, description, delivery)).add(snapshot);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            buffers.asMap().values().forEach(Buffer::close);
            buffers.invalidateAll();
            timer.shutdownNow();
            worker.shutdown();
        } finally {
            lifecycle.writeLock().unlock();
        }
        try {
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Batch worker did not finish within 30 seconds; queued deliveries are still running");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for batch deliveries at shutdown", exception);
        }
    }

    @FunctionalInterface
    interface Delivery {
        void invoke(List<Object> elements) throws Throwable;
    }

    private final class Buffer {

        private final Batch settings;
        private final String description;
        private final Delivery delivery;
        private List<Object> pending = new ArrayList<>();
        private ScheduledFuture<?> timeoutTask;
        private long generation;
        private boolean stopped;

        private Buffer(Batch settings, String description, Delivery delivery) {
            this.settings = settings;
            this.description = description;
            this.delivery = delivery;
        }

        synchronized void add(List<?> elements) {
            int offset = 0;
            while (offset < elements.size()) {
                int count = Math.min(settings.maxSize() - pending.size(), elements.size() - offset);
                pending.addAll(elements.subList(offset, offset + count));
                offset += count;
                if (pending.size() == settings.maxSize()) {
                    flush();
                } else if (timeoutTask == null) {
                    long currentGeneration = ++generation;
                    timeoutTask = timer.schedule(() -> onTimeout(currentGeneration),
                            settings.timeout(), settings.timeUnit());
                }
            }
        }

        private synchronized void onTimeout(long expectedGeneration) {
            // Cancellation alone cannot stop a timer that has already started running.
            if (!stopped && expectedGeneration == generation) {
                flush();
            }
        }

        private void flush() {
            generation++;
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
                timeoutTask = null;
            }
            if (pending.isEmpty()) {
                return;
            }
            List<Object> batch = pending;
            pending = new ArrayList<>();
            worker.execute(() -> {
                try {
                    delivery.invoke(batch);
                } catch (Throwable exception) {
                    logger.error("Asynchronous batch delivery failed for {} ({} elements)",
                            description, batch.size(), exception);
                }
            });
        }

        synchronized void close() {
            stopped = true;
            flush();
        }
    }
}
