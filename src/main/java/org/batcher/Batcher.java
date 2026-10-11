package org.batcher;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Creates generated batching interfaces without Spring or invocation proxies. */
public final class Batcher {

    private Batcher() {
    }

    public static <T, I extends BatchInput<T>> I init(Class<I> inputType, BatchSettings settings,
            Consumer<? super List<T>> handler) {
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(handler, "handler");
        if (!inputType.isInterface() || !BatchInput.class.isAssignableFrom(inputType)) {
            throw new IllegalArgumentException("Input must be an interface extending BatchInput<T>: " + inputType);
        }
        try {
            Class<?> implementation = Class.forName(inputType.getName() + "Impl", true, inputType.getClassLoader());
            if (!inputType.isAssignableFrom(implementation)) {
                throw new IllegalArgumentException("Generated implementation does not implement " + inputType.getName());
            }
            return inputType.cast(implementation.getConstructor(BatchSettings.class, Consumer.class)
                    .newInstance(settings, handler));
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("No generated implementation for " + inputType.getName()
                    + "; add @BatchInputType and enable org.batcher.processor.BatchInputProcessor", exception);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (exception.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException("Cannot initialize " + inputType.getName(), exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot initialize generated implementation for " + inputType.getName(), exception);
        }
    }

    /** Runtime entry point used by generated int implementations; boxes only on delivery. */
    public static final class IntBuffer extends InputBuffer<Integer> {

        private int[] pending;

        public IntBuffer(BatchSettings settings, Consumer<? super List<Integer>> handler) {
            super(settings, handler);
        }

        public synchronized void add(int value) {
            ensureOpen();
            if (pending == null) {
                pending = new int[settings.maxSize()];
            }
            pending[size] = value;
            elementAdded();
        }

        @Override
        protected Supplier<List<Integer>> detach() {
            int[] values = pending;
            int count = size;
            pending = null;
            return () -> {
                List<Integer> batch = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    batch.add(values[i]);
                }
                return batch;
            };
        }
    }

    /** Runtime entry point used by generated reference-type implementations. */
    public static final class ObjectBuffer<T> extends InputBuffer<T> {

        private List<T> pending;

        public ObjectBuffer(BatchSettings settings, Consumer<? super List<T>> handler) {
            super(settings, handler);
        }

        public synchronized void add(T value) {
            ensureOpen();
            if (pending == null) {
                pending = new ArrayList<>(settings.maxSize());
            }
            pending.add(value);
            elementAdded();
        }

        @Override
        protected Supplier<List<T>> detach() {
            List<T> batch = pending;
            pending = null;
            return () -> batch;
        }
    }
}
