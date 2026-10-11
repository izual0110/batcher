package org.batcher;

/** Typed lifecycle contract for a generated, single-element batching interface. */
public interface BatchInput<T> extends AutoCloseable {

    /** Stops accepting elements, flushes the remainder and waits for deliveries. */
    @Override
    void close();
}
