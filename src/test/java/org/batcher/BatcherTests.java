package org.batcher;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
public class BatcherTests {

    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);

    @BatchInputType
    public interface NumberInput extends BatchInput<Integer> {
        void foo(int value);
    }

    @BatchInputType
    public interface TextInput extends BatchInput<String> {
        void accept(String value);
    }

    public record User(String name) {
    }

    public record Envelope<T>(T value) {
    }

    @BatchInputType
    public interface UserInput extends BatchInput<User> {
        void foo(User value);
    }

    @BatchInputType
    public interface EnvelopeInput extends BatchInput<Envelope<User>> {
        void accept(Envelope<User> value);
    }

    @BatchInputType
    public interface ObjectInput extends BatchInput<Object> {
        void accept(Object value);
    }

    @BatchInputType
    public interface ListInput extends BatchInput<List<? extends Number>> {
        void accept(List<? extends Number> value);
    }

    public interface UnprocessedNumberInput extends BatchInput<Integer> {
        void foo(int value);
    }

    @Test
    void userObjectsAreBatchedWithoutCopyingOrWrappingThem() throws Exception {
        BlockingQueue<List<User>> deliveries = new LinkedBlockingQueue<>();
        User first = new User("first");
        User second = new User("second");
        List<User> batch;
        try (var input = Batcher.init(UserInput.class, new BatchSettings(3, LONG_TIMEOUT), deliveries::add)) {
            input.foo(first);
            input.foo(second);
            input.foo(first);
            batch = next(deliveries);
            assertEquals(UserInput.class.getName() + "Impl", input.getClass().getName());
            assertSame(first, batch.get(0));
            assertSame(second, batch.get(1));
            assertSame(first, batch.get(2));
            input.foo(second);
        }
        assertEquals(List.of(first, second, first), batch);
        assertSame(second, next(deliveries).getFirst());
    }

    @Test
    void parameterizedPayloadsKeepTheirTypeAndIdentity() throws Exception {
        BlockingQueue<List<Envelope<User>>> deliveries = new LinkedBlockingQueue<>();
        Envelope<User> envelope = new Envelope<>(new User("wrapped"));
        try (var input = Batcher.init(EnvelopeInput.class, new BatchSettings(1, LONG_TIMEOUT), deliveries::add)) {
            input.accept(envelope);
            assertSame(envelope, next(deliveries).getFirst());
        }
    }

    @Test
    void objectPayloadsCanMixUnrelatedRuntimeTypes() throws Exception {
        BlockingQueue<List<Object>> deliveries = new LinkedBlockingQueue<>();
        User user = new User("user");
        Object other = new Object();
        int[] array = {1, 2};
        try (var input = Batcher.init(ObjectInput.class, new BatchSettings(5, LONG_TIMEOUT), deliveries::add)) {
            input.accept(user);
            input.accept(other);
            input.accept(array);
            input.accept("text");
            input.accept(null);
            List<Object> batch = next(deliveries);
            assertSame(user, batch.get(0));
            assertSame(other, batch.get(1));
            assertSame(array, batch.get(2));
            assertEquals("text", batch.get(3));
            assertNull(batch.get(4));
        }
    }

    @Test
    void wildcardParameterizedPayloadsAcceptDifferentSubtypesWithoutCopying() throws Exception {
        BlockingQueue<List<List<? extends Number>>> deliveries = new LinkedBlockingQueue<>();
        List<Integer> integers = List.of(1, 2);
        List<Double> doubles = List.of(1.5, 2.5);
        try (var input = Batcher.init(ListInput.class, new BatchSettings(2, LONG_TIMEOUT), deliveries::add)) {
            input.accept(integers);
            input.accept(doubles);
            List<List<? extends Number>> batch = next(deliveries);
            assertSame(integers, batch.get(0));
            assertSame(doubles, batch.get(1));
        }
    }

    @Test
    void initLoadsTheGeneratedBinaryNameInsteadOfAProxy() throws Exception {
        try (NumberInput input = Batcher.init(NumberInput.class, BatchSettings.defaults(), batch -> { })) {
            assertFalse(Proxy.isProxyClass(input.getClass()));
            assertEquals(NumberInput.class.getName() + "Impl", input.getClass().getName());
            assertSame(NumberInput.class.getClassLoader(), input.getClass().getClassLoader());
            assertEquals(void.class, input.getClass().getMethod("foo", int.class).getReturnType());
        }
    }

    @Test
    void reachingMaxSizeDeliversAsynchronouslyAndPreservesDuplicatesAndOrder() throws Exception {
        BlockingQueue<Delivery<Integer>> deliveries = new LinkedBlockingQueue<>();
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(3, LONG_TIMEOUT),
                batch -> deliveries.add(Delivery.capture(batch)))) {
            input.foo(7);
            input.foo(7);
            assertTrue(deliveries.isEmpty());
            input.foo(-1);

            Delivery<Integer> delivery = next(deliveries);
            assertEquals(List.of(7, 7, -1), delivery.values());
            assertNotSame(Thread.currentThread(), delivery.thread());
            assertNull(deliveries.poll(100, TimeUnit.MILLISECONDS));
        }
        assertTrue(deliveries.isEmpty(), "Closing an empty input must not deliver an empty batch");
    }

    @Test
    void sizeTriggeredAddReturnsWhileTheHandlerIsStillRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(1, LONG_TIMEOUT), batch -> {
            entered.countDown();
            await(release);
        });
        try {
            Future<?> addition = caller.submit(() -> input.foo(1));
            await(entered);
            addition.get(500, TimeUnit.MILLISECONDS);
            assertEquals(1, release.getCount(), "The add must return without waiting for the handler");
        } finally {
            release.countDown();
            try {
                input.close();
            } finally {
                shutdown(caller);
            }
        }
    }

    @Test
    void timeoutDeliversAPartialBatchWithoutAnotherAdd() throws Exception {
        BlockingQueue<Delivery<Integer>> deliveries = new LinkedBlockingQueue<>();
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(10, Duration.ofMillis(150)),
                batch -> deliveries.add(Delivery.capture(batch)))) {
            input.foo(42);

            Delivery<Integer> delivery = next(deliveries);
            assertEquals(List.of(42), delivery.values());
            assertNotSame(Thread.currentThread(), delivery.thread());
            assertNull(deliveries.poll(200, TimeUnit.MILLISECONDS), "An idle timer must not deliver empty batches");
        }
    }

    @Test
    void timeoutRunsFromTheFirstElementRatherThanTheLatestAdd() throws Exception {
        BlockingQueue<List<Integer>> deliveries = new LinkedBlockingQueue<>();
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(10, Duration.ofMillis(1200)),
                deliveries::add)) {
            input.foo(1);
            assertNull(deliveries.poll(750, TimeUnit.MILLISECONDS));
            input.foo(2);

            // The original deadline is about 450 ms away; a reset deadline would be 1,200 ms away.
            List<Integer> batch = deliveries.poll(800, TimeUnit.MILLISECONDS);
            assertNotNull(batch, "Adding another element must not reset the first element's timeout");
            assertEquals(List.of(1, 2), batch);
        }
    }

    @Test
    void closeFlushesTheRemainderAndDrainsQueuedDeliveriesInOrder() {
        BlockingQueue<List<Integer>> deliveries = new LinkedBlockingQueue<>();
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(2, LONG_TIMEOUT), deliveries::add)) {
            input.foo(1);
            input.foo(2);
            input.foo(3);
            input.foo(4);
            input.foo(5);
        }

        assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5)), new ArrayList<>(deliveries));
    }

    @Test
    void closeWaitsForTheFlushedHandlerToFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        BlockingQueue<List<Integer>> completed = new LinkedBlockingQueue<>();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(10, LONG_TIMEOUT), batch -> {
            entered.countDown();
            await(release);
            completed.add(batch);
        });
        try {
            input.foo(9);
            Future<?> close = closer.submit(() -> {
                closeStarted.countDown();
                input.close();
            });
            await(closeStarted);
            await(entered);
            assertThrows(TimeoutException.class, () -> close.get(150, TimeUnit.MILLISECONDS));
            assertTrue(completed.isEmpty());

            release.countDown();
            close.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(List.of(9)), new ArrayList<>(completed));
        } finally {
            release.countDown();
            try {
                input.close();
            } finally {
                shutdown(closer);
            }
        }
    }

    @Test
    void repeatedCloseDoesNotRedeliverAndAddsAfterCloseFail() {
        BlockingQueue<List<Integer>> deliveries = new LinkedBlockingQueue<>();
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(10, LONG_TIMEOUT), deliveries::add)) {
            input.foo(1);
            input.close();
            input.close();
            assertThrows(IllegalStateException.class, () -> input.foo(2));
        }

        assertEquals(List.of(List.of(1)), new ArrayList<>(deliveries));
    }

    @Test
    void closingAnUnusedInputNeverInvokesTheHandler() {
        AtomicInteger calls = new AtomicInteger();
        NumberInput input = Batcher.init(NumberInput.class, BatchSettings.defaults(), batch -> calls.incrementAndGet());
        input.close();
        input.close();

        assertEquals(0, calls.get());
    }

    @Test
    void retainedIntegerListsAreNotReusedForLaterBatches() throws Exception {
        BlockingQueue<List<Integer>> deliveries = new LinkedBlockingQueue<>();
        List<Integer> first;
        List<Integer> second;
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(2, LONG_TIMEOUT), deliveries::add)) {
            input.foo(Integer.MIN_VALUE);
            input.foo(Integer.MAX_VALUE);
            first = next(deliveries);
            input.foo(3);
            input.foo(4);
            second = next(deliveries);
            input.foo(5);
        }

        List<Integer> remainder = next(deliveries);
        assertEquals(List.of(Integer.MIN_VALUE, Integer.MAX_VALUE), first);
        assertEquals(List.of(3, 4), second);
        assertEquals(List.of(5), remainder);
        assertNotSame(first, second);
        assertNotSame(second, remainder);
    }

    @Test
    void referenceInputsPreserveNullsDuplicatesIdentityAndRetainedLists() throws Exception {
        BlockingQueue<List<String>> deliveries = new LinkedBlockingQueue<>();
        String value = new String("retained-reference");
        List<String> first;
        List<String> second;
        try (TextInput input = Batcher.init(TextInput.class, new BatchSettings(3, LONG_TIMEOUT), deliveries::add)) {
            assertEquals(TextInput.class.getName() + "Impl", input.getClass().getName());
            assertFalse(Proxy.isProxyClass(input.getClass()));
            input.accept(value);
            input.accept(null);
            input.accept(value);
            first = next(deliveries);
            input.accept("later");
            input.accept("later");
            input.accept(null);
            second = next(deliveries);
            input.accept(null);
        }

        List<String> remainder = next(deliveries);
        assertEquals(Arrays.asList(value, null, value), first);
        assertSame(value, first.get(0));
        assertSame(value, first.get(2));
        assertEquals(Arrays.asList("later", "later", null), second);
        assertEquals(Arrays.asList((String) null), remainder);
        assertNotSame(first, second);
        assertNotSame(second, remainder);
        assertTrue(deliveries.isEmpty());
    }

    @Test
    void referenceTimeoutDeliversNullAndClosedReferenceAddsFail() throws Exception {
        BlockingQueue<List<String>> deliveries = new LinkedBlockingQueue<>();
        try (TextInput input = Batcher.init(TextInput.class, new BatchSettings(5, Duration.ofMillis(100)), deliveries::add)) {
            input.accept(null);
            assertEquals(Arrays.asList((String) null), next(deliveries));
            input.close();
            assertThrows(IllegalStateException.class, () -> input.accept(null));
            assertThrows(IllegalStateException.class, () -> input.accept("closed"));
        }
        assertTrue(deliveries.isEmpty());
    }

    @Test
    void inputsOfTheSameTypeHaveIndependentBuffersTimersAndWorkers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<Delivery<Integer>> firstDeliveries = new LinkedBlockingQueue<>();
        BlockingQueue<Delivery<Integer>> secondDeliveries = new LinkedBlockingQueue<>();
        NumberInput first = Batcher.init(NumberInput.class, new BatchSettings(1, LONG_TIMEOUT), batch -> {
            entered.countDown();
            await(release);
            firstDeliveries.add(Delivery.capture(batch));
        });
        try (NumberInput second = Batcher.init(NumberInput.class, new BatchSettings(10, Duration.ofMillis(100)),
                batch -> secondDeliveries.add(Delivery.capture(batch)))) {
            assertNotSame(first, second);
            first.foo(1);
            await(entered);
            second.foo(2);

            Delivery<Integer> independent = next(secondDeliveries);
            assertEquals(List.of(2), independent.values());
            assertTrue(firstDeliveries.isEmpty(), "Another input must deliver even while the first handler is blocked");
            second.close();
            first.foo(3);
            release.countDown();
            first.close();

            Delivery<Integer> initial = next(firstDeliveries);
            Delivery<Integer> later = next(firstDeliveries);
            assertEquals(List.of(1), initial.values());
            assertEquals(List.of(3), later.values());
            assertSame(initial.thread(), later.thread());
            assertNotSame(initial.thread(), independent.thread());
        } finally {
            release.countDown();
            first.close();
        }
    }

    @Test
    void concurrentProducersLoseOrDuplicateNoElementsAndKeepEachProducersOrder() throws Exception {
        int producerCount = 6;
        int elementsPerProducer = 300;
        BlockingQueue<List<Integer>> deliveries = new LinkedBlockingQueue<>();
        ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        CountDownLatch ready = new CountDownLatch(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(37, LONG_TIMEOUT), deliveries::add)) {
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int producer = 0; producer < producerCount; producer++) {
                    int offset = producer * elementsPerProducer;
                    tasks.add(producers.submit(() -> {
                        ready.countDown();
                        await(start);
                        for (int element = 0; element < elementsPerProducer; element++) {
                            input.foo(offset + element);
                        }
                    }));
                }
                await(ready);
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get(3, TimeUnit.SECONDS);
                }
            } finally {
                start.countDown();
                shutdown(producers);
            }
        }

        List<Integer> received = deliveries.stream().flatMap(List::stream).toList();
        int total = producerCount * elementsPerProducer;
        assertEquals(total, received.size());
        assertEquals(new HashSet<>(IntStream.range(0, total).boxed().toList()), new HashSet<>(received));
        assertEquals((total + 36) / 37, deliveries.size());
        List<List<Integer>> batches = new ArrayList<>(deliveries);
        for (int index = 0; index < batches.size() - 1; index++) {
            assertEquals(37, batches.get(index).size());
        }
        assertEquals(total % 37, batches.getLast().size());
        for (int producer = 0; producer < producerCount; producer++) {
            int offset = producer * elementsPerProducer;
            assertEquals(IntStream.range(offset, offset + elementsPerProducer).boxed().toList(),
                    received.stream().filter(value -> value >= offset && value < offset + elementsPerProducer).toList());
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void handlerFailuresAreLoggedAndDoNotStopLaterDeliveries(CapturedOutput output) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BlockingQueue<Delivery<Integer>> deliveries = new LinkedBlockingQueue<>();
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(1, LONG_TIMEOUT), batch -> {
            deliveries.add(Delivery.capture(batch));
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("batcher-runtime-test-handler-failure");
            }
        })) {
            input.foo(1);
            input.foo(2);
            Delivery<Integer> failed = next(deliveries);
            Delivery<Integer> successful = next(deliveries);
            assertEquals(List.of(1), failed.values());
            assertEquals(List.of(2), successful.values());
            assertSame(failed.thread(), successful.thread());
        }

        assertEquals(2, calls.get());
        assertTrue(output.getAll().contains("Asynchronous batch input delivery failed"));
        assertTrue(output.getAll().contains("batcher-runtime-test-handler-failure"));
    }

    @Test
    void aSlowHandlerDoesNotBlockTheTimerAndDeliveriesRemainSequential() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BlockingQueue<Delivery<Integer>> deliveries = new LinkedBlockingQueue<>();
        NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(2, Duration.ofMillis(100)), batch -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
            deliveries.add(Delivery.capture(batch));
        });
        try {
            input.foo(1);
            input.foo(2);
            await(entered);
            input.foo(3);
            assertNull(deliveries.poll(500, TimeUnit.MILLISECONDS));
            assertEquals(1, calls.get(), "Handlers for one input must not run concurrently");
            // The timer must have detached [3] while the worker was blocked, rather than combining it with [4].
            input.foo(4);
            release.countDown();
            input.close();

            Delivery<Integer> first = next(deliveries);
            Delivery<Integer> second = next(deliveries);
            Delivery<Integer> third = next(deliveries);
            assertEquals(List.of(1, 2), first.values());
            assertEquals(List.of(3), second.values());
            assertEquals(List.of(4), third.values());
            assertSame(first.thread(), second.thread());
            assertSame(second.thread(), third.thread());
            assertTrue(deliveries.isEmpty());
        } finally {
            release.countDown();
            input.close();
        }
    }

    @Test
    void aHandlerCanUseAConsumerOfASupertypeOfList() throws Exception {
        BlockingQueue<Object> deliveries = new LinkedBlockingQueue<>();
        Consumer<Object> handler = deliveries::add;
        try (NumberInput input = Batcher.init(NumberInput.class, new BatchSettings(1, LONG_TIMEOUT), handler)) {
            input.foo(5);
            assertEquals(List.of(5), next(deliveries));
        }
    }

    @Test
    void settingsDefaultsAndValidValuesMatchTheContract() {
        assertEquals(new BatchSettings(100, Duration.ofMillis(100)), BatchSettings.defaults());
        BatchSettings settings = new BatchSettings(1, Duration.ofNanos(1));
        assertEquals(1, settings.maxSize());
        assertEquals(Duration.ofNanos(1), settings.timeout());
    }

    @Test
    void invalidSettingsFailImmediately() {
        assertThrows(IllegalArgumentException.class, () -> new BatchSettings(0, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new BatchSettings(-1, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new BatchSettings(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BatchSettings(1, Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> new BatchSettings(1, Duration.ofSeconds(Long.MAX_VALUE)));
        NullPointerException exception = assertThrows(NullPointerException.class, () -> new BatchSettings(1, null));
        assertEquals("timeout", exception.getMessage());
    }

    @Test
    void missingGeneratedImplementationExplainsHowToEnableGeneration() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Batcher.init(UnprocessedNumberInput.class, BatchSettings.defaults(), batch -> { }));

        assertTrue(exception.getMessage().contains(UnprocessedNumberInput.class.getName()));
        assertTrue(exception.getMessage().contains("@BatchInputType"));
        assertTrue(exception.getMessage().contains("org.batcher.processor.BatchInputProcessor"));
        assertInstanceOf(ClassNotFoundException.class, exception.getCause());
    }

    @Test
    void nullInitArgumentsAreRejectedWithTheirArgumentNames() {
        NullPointerException type = assertThrows(NullPointerException.class,
                () -> Batcher.init(null, BatchSettings.defaults(), batch -> { }));
        NullPointerException settings = assertThrows(NullPointerException.class,
                () -> Batcher.init(NumberInput.class, null, batch -> { }));
        NullPointerException handler = assertThrows(NullPointerException.class,
                () -> Batcher.init(NumberInput.class, BatchSettings.defaults(), null));

        assertEquals("inputType", type.getMessage());
        assertEquals("settings", settings.getMessage());
        assertEquals("handler", handler.getMessage());
    }

    private static <T> T next(BlockingQueue<T> deliveries) throws InterruptedException {
        T delivery = deliveries.poll(3, TimeUnit.SECONDS);
        assertNotNull(delivery, "Expected a batch delivery within three seconds");
        return delivery;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "Timed out waiting for the test latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the test latch", exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "Test executor did not terminate");
    }

    private record Delivery<T>(List<T> values, Thread thread) {
        private static <T> Delivery<T> capture(List<T> values) {
            return new Delivery<>(values, Thread.currentThread());
        }
    }
}
