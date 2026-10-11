package org.batcher;

import java.time.Duration;
import java.util.Objects;

/** Batch element limit and maximum wait measured from the first element. */
public record BatchSettings(int maxSize, Duration timeout) {

    public BatchSettings {
        Objects.requireNonNull(timeout, "timeout");
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timeout must fit in nanoseconds", exception);
        }
    }

    public static BatchSettings defaults() {
        return new BatchSettings(100, Duration.ofMillis(100));
    }
}
