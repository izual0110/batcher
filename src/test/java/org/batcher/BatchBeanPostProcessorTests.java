package org.batcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.batcher.autoconfigure.BatchAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
class BatchBeanPostProcessorTests {

    private BatchBeanPostProcessor processor;

    @BeforeEach
    void createProcessor() {
        processor = new BatchBeanPostProcessor();
    }

    @AfterEach
    void destroyProcessor() throws Exception {
        if (processor != null) {
            BatchBeanPostProcessor toDestroy = processor;
            processor = null;
            toDestroy.destroy();
        }
    }

    @Test
    void annotationDefaultsMatchThePublicContract() throws Exception {
        Method method = DefaultBean.class.getMethod("accept", List.class);
        Batch annotation = method.getAnnotation(Batch.class);

        assertEquals(100L, annotation.timeout());
        assertEquals(100, annotation.maxSize());
        assertEquals(TimeUnit.MILLISECONDS, annotation.timeUnit());
    }

    @Test
    void sizeCountsElementsAcrossCallsAndDeliveryIsAsynchronous() throws Exception {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "recording", RecordingBean.class);
        assertTrue(AopUtils.isCglibProxy(proxy));

        proxy.accept(List.of(1, 2));
        assertTrue(target.deliveries.isEmpty());
        proxy.accept(List.of(3));

        Delivery delivery = next(target.deliveries);
        assertEquals(List.of(1, 2, 3), delivery.values());
        assertNotSame(Thread.currentThread(), delivery.thread());
        assertNull(target.deliveries.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void timeoutDeliversAPartialBatchWithoutAnotherInvocation() throws Exception {
        TimeoutBean target = new TimeoutBean();
        TimeoutBean proxy = process(target, "timeout", TimeoutBean.class);

        proxy.accept(List.of(1, 2));

        Delivery delivery = next(target.deliveries);
        assertEquals(List.of(1, 2), delivery.values());
        assertNotSame(Thread.currentThread(), delivery.thread());
    }

    @Test
    void timeoutHonorsTheConfiguredTimeUnit() throws Exception {
        SecondsTimeoutBean target = new SecondsTimeoutBean();
        SecondsTimeoutBean proxy = process(target, "secondsTimeout", SecondsTimeoutBean.class);

        proxy.accept(List.of(1));

        assertNull(target.deliveries.poll(200, TimeUnit.MILLISECONDS),
                "A one-second timeout must not be interpreted as milliseconds");
        assertEquals(List.of(1), next(target.deliveries).values());
    }

    @Test
    void timeoutRunsFromFirstElementRatherThanResettingOnEveryCall() throws Exception {
        FixedTimeoutBean target = new FixedTimeoutBean();
        FixedTimeoutBean proxy = process(target, "fixedTimeout", FixedTimeoutBean.class);

        proxy.accept(List.of(1));
        assertNull(target.deliveries.poll(800, TimeUnit.MILLISECONDS));
        proxy.accept(List.of(2));

        // The first deadline is about 400 ms away; a reset deadline is 1,200 ms away.
        Delivery delivery = target.deliveries.poll(800, TimeUnit.MILLISECONDS);
        assertNotNull(delivery, "Adding an element must not reset the first element's timeout");
        assertEquals(List.of(1, 2), delivery.values());
    }

    @Test
    void largeCollectionIsSplitAtTheElementCapAndRemainderIsFlushed() throws Exception {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "largeInput", RecordingBean.class);

        proxy.accept(List.of(1, 2, 3, 4, 5, 6, 7, 8));

        assertEquals(List.of(1, 2, 3), next(target.deliveries).values());
        assertEquals(List.of(4, 5, 6), next(target.deliveries).values());
        assertTrue(target.deliveries.isEmpty());
        destroyProcessor();
        assertEquals(List.of(7, 8), next(target.deliveries).values());
        assertTrue(target.deliveries.isEmpty());
    }

    @Test
    void duplicatesAndEncounterOrderArePreserved() throws Exception {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "duplicates", RecordingBean.class);

        proxy.accept(List.of(2, 1));
        proxy.accept(List.of(2, 3, 3, 1));

        assertEquals(List.of(2, 1, 2), next(target.deliveries).values());
        assertEquals(List.of(3, 3, 1), next(target.deliveries).values());
    }

    @Test
    void callerCollectionIsCopiedBeforeTheInvocationReturns() throws Exception {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "copiedInput", RecordingBean.class);
        List<Integer> input = new ArrayList<>(List.of(1, 2));

        proxy.accept(input);
        input.clear();
        input.add(99);
        proxy.accept(List.of(3));

        assertEquals(List.of(1, 2, 3), next(target.deliveries).values());
        assertEquals(List.of(99), input);
    }

    @Test
    void emptyCollectionsAreIgnoredAndDoNotProduceAnEmptyBatch() throws Exception {
        TimeoutBean target = new TimeoutBean();
        TimeoutBean proxy = process(target, "emptyInput", TimeoutBean.class);

        proxy.accept(List.of());
        assertNull(target.deliveries.poll(300, TimeUnit.MILLISECONDS));
        destroyProcessor();
        assertTrue(target.deliveries.isEmpty());
    }

    @Test
    void nullCollectionFailsSynchronouslyWithoutPoisoningSubsequentCalls() throws Exception {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "nullInput", RecordingBean.class);

        assertThrows(IllegalArgumentException.class, () -> proxy.accept(null));
        proxy.accept(List.of(1, 2, 3));

        assertEquals(List.of(1, 2, 3), next(target.deliveries).values());
    }

    @Test
    void separateBeansOfTheSameClassHaveIndependentBuffers() throws Exception {
        RecordingBean firstTarget = new RecordingBean();
        RecordingBean secondTarget = new RecordingBean();
        RecordingBean first = process(firstTarget, "firstBean", RecordingBean.class);
        RecordingBean second = process(secondTarget, "secondBean", RecordingBean.class);

        first.accept(List.of(1, 2));
        second.accept(List.of(10));
        second.accept(List.of(11, 12));

        assertEquals(List.of(10, 11, 12), next(secondTarget.deliveries).values());
        assertTrue(firstTarget.deliveries.isEmpty());
        first.accept(List.of(3));
        assertEquals(List.of(1, 2, 3), next(firstTarget.deliveries).values());
    }

    @Test
    void separateMethodsOnTheSameBeanHaveIndependentBuffers() throws Exception {
        TwoMethodsBean target = new TwoMethodsBean();
        TwoMethodsBean proxy = process(target, "twoMethods", TwoMethodsBean.class);

        proxy.first(List.of(1, 2));
        proxy.second(List.of(10, 11, 12));

        assertEquals(List.of(10, 11, 12), next(target.secondDeliveries).values());
        assertTrue(target.firstDeliveries.isEmpty());
        proxy.first(List.of(3));
        assertEquals(List.of(1, 2, 3), next(target.firstDeliveries).values());
    }

    @Test
    void unannotatedMethodsOnABatchedBeanAreInvokedDirectly() {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "directMethod", RecordingBean.class);
        List<Integer> input = new ArrayList<>(List.of(1));

        assertSame(input, proxy.direct(input));
        assertSame(Thread.currentThread(), target.directThread);
        assertEquals(1, target.directCalls.get());
        assertTrue(target.deliveries.isEmpty());
    }

    @Test
    void beanWithoutAnnotatedMethodsIsReturnedUnchanged() {
        Object target = new Object();

        assertSame(target, processor.postProcessAfterInitialization(target, "plain"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBeans")
    void invalidSignaturesAndSettingsFailDuringPostProcessing(String description, Object target) {
        assertThrows(BeanCreationException.class,
                () -> processor.postProcessAfterInitialization(target, description));
    }

    static Stream<Arguments> invalidBeans() {
        return Stream.of(
                Arguments.of("private method", new PrivateMethodBean()),
                Arguments.of("protected method", new ProtectedMethodBean()),
                Arguments.of("package-private method", new PackagePrivateMethodBean()),
                Arguments.of("static method", new StaticMethodBean()),
                Arguments.of("final method", new FinalMethodBean()),
                Arguments.of("non-void return", new ReturningMethodBean()),
                Arguments.of("no arguments", new NoArgumentsBean()),
                Arguments.of("multiple arguments", new MultipleArgumentsBean()),
                Arguments.of("scalar argument", new ScalarArgumentBean()),
                Arguments.of("set argument", new SetArgumentBean()),
                Arguments.of("concrete collection argument", new ConcreteCollectionBean()),
                Arguments.of("zero maxSize", new ZeroSizeBean()),
                Arguments.of("negative maxSize", new NegativeSizeBean()),
                Arguments.of("zero timeout", new ZeroTimeoutBean()),
                Arguments.of("negative timeout", new NegativeTimeoutBean()));
    }

    @Test
    void concurrentProducersLoseOrDuplicateNoElements() throws Exception {
        RecordingBean target = new RecordingBean();
        RecordingBean proxy = process(target, "concurrent", RecordingBean.class);
        int producers = 6;
        int elementsPerProducer = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(producers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int producer = 0; producer < producers; producer++) {
                int offset = producer * elementsPerProducer;
                futures.add(executor.submit(() -> {
                    await(start);
                    for (int element = 0; element < elementsPerProducer; element++) {
                        proxy.accept(List.of(offset + element));
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            destroyProcessor();

            List<Integer> actual = new ArrayList<>();
            Delivery delivery;
            while ((delivery = target.deliveries.poll()) != null) {
                assertTrue(delivery.values().size() <= 3);
                actual.addAll(delivery.values());
            }
            int total = producers * elementsPerProducer;
            assertEquals(total, actual.size());
            assertEquals(new HashSet<>(IntStream.range(0, total).boxed().toList()), new HashSet<>(actual));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownFlushesPendingBatchesForEveryBeanAndMethod() throws Exception {
        RecordingBean firstTarget = new RecordingBean();
        TwoMethodsBean secondTarget = new TwoMethodsBean();
        RecordingBean first = process(firstTarget, "shutdownFirst", RecordingBean.class);
        TwoMethodsBean second = process(secondTarget, "shutdownSecond", TwoMethodsBean.class);

        first.accept(List.of(1));
        second.first(List.of(2));
        second.second(List.of(3));
        destroyProcessor();

        // Inspect immediately: destroy must return only after deliveries finish.
        assertEquals(List.of(1), firstTarget.deliveries.remove().values());
        assertEquals(List.of(2), secondTarget.firstDeliveries.remove().values());
        assertEquals(List.of(3), secondTarget.secondDeliveries.remove().values());
    }

    @Test
    void shutdownWaitsForTheFlushedInvocationToComplete() throws Exception {
        BlockingBean target = new BlockingBean();
        BlockingBean proxy = process(target, "blockingShutdown", BlockingBean.class);
        proxy.accept(List.of(1));
        BatchBeanPostProcessor toDestroy = processor;
        processor = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> shutdown = executor.submit(() -> {
                toDestroy.destroy();
                return null;
            });
            assertTrue(target.entered.await(5, TimeUnit.SECONDS), "Shutdown should flush the partial batch");
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            target.release.countDown();
            shutdown.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1), target.deliveries.remove().values());
        } finally {
            target.release.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void oneWorkerSerializesBeansWhileTimeoutsStillExpireIndependently() throws Exception {
        BlockingBean blockingTarget = new BlockingBean();
        TimeoutBean timedTarget = new TimeoutBean();
        BlockingBean blocking = process(blockingTarget, "blockedWorker", BlockingBean.class);
        TimeoutBean timed = process(timedTarget, "independentTimer", TimeoutBean.class);
        try {
            blocking.accept(List.of(10, 11, 12));
            assertTrue(blockingTarget.entered.await(5, TimeUnit.SECONDS));
            timed.accept(List.of(1));
            // No delivery can run while the sole worker is blocked, but the timer must detach [1].
            assertNull(timedTarget.deliveries.poll(350, TimeUnit.MILLISECONDS));
            timed.accept(List.of(2));
            blockingTarget.release.countDown();

            Delivery blockedDelivery = next(blockingTarget.deliveries);
            Delivery firstTimedDelivery = next(timedTarget.deliveries);
            Delivery secondTimedDelivery = next(timedTarget.deliveries);
            assertEquals(List.of(1), firstTimedDelivery.values());
            assertEquals(List.of(2), secondTimedDelivery.values());
            assertSame(blockedDelivery.thread(), firstTimedDelivery.thread());
            assertSame(firstTimedDelivery.thread(), secondTimedDelivery.thread());
        } finally {
            blockingTarget.release.countDown();
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void asynchronousFailureIsLoggedAndDoesNotStopLaterBatches(CapturedOutput output) throws Exception {
        FailingBean target = new FailingBean();
        FailingBean proxy = process(target, "failing", FailingBean.class);

        proxy.accept(List.of(1));
        proxy.accept(List.of(2));

        assertEquals(List.of(2), next(target.deliveries).values());
        assertEquals(2, target.calls.get());
        assertTrue(output.getAll().contains("Deliberate asynchronous test failure"),
                "The asynchronous exception should be logged with its cause");
    }

    @Test
    void annotationDeclaredOnlyOnAnInterfaceIsRecognized() throws Exception {
        InterfaceBean target = new InterfaceBean();
        BatchApi proxy = process(target, "interfaceAnnotation", BatchApi.class);

        proxy.accept(List.of(1));
        assertTrue(target.deliveries.isEmpty());
        proxy.accept(List.of(2));

        assertEquals(List.of(1, 2), next(target.deliveries).values());
    }

    @Test
    void existingJdkSpringProxyRetainsItsAdviceAndSupportsInterfaceAnnotation() throws Exception {
        InterfaceBean target = new InterfaceBean();
        AtomicInteger adviceCalls = new AtomicInteger();
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(BatchApi.class);
        factory.setProxyTargetClass(false);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            adviceCalls.incrementAndGet();
            return invocation.proceed();
        });
        Object existingProxy = factory.getProxy();
        assertTrue(AopUtils.isJdkDynamicProxy(existingProxy));
        BatchApi proxy = process(existingProxy, "existingJdkProxy", BatchApi.class);

        proxy.accept(List.of(1, 2));

        assertEquals(List.of(1, 2), next(target.deliveries).values());
        // Advice may surround admission or delivery; it must not be bypassed entirely.
        assertTrue(adviceCalls.get() > 0);
    }

    @Test
    void staticProcessorBeanIntegratesWithSpringAndContextCloseFlushesPendingData() {
        RecordingBean target;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            RecordingBean proxy = context.getBean(RecordingBean.class);
            target = context.getBean(TestConfiguration.class).target;
            assertTrue(AopUtils.isCglibProxy(proxy));
            proxy.accept(List.of(7));
            assertTrue(target.deliveries.isEmpty());
        }
        assertEquals(List.of(7), target.deliveries.remove().values());
    }

    @Test
    void enableBatchingRegistersAProcessorAndBatchesAnnotatedBeans() throws Exception {
        RecordingBean target;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(EnabledBatchingConfiguration.class)) {
            assertEquals(1, context.getBeansOfType(BatchBeanPostProcessor.class).size());
            target = context.getBean(EnabledBatchingConfiguration.class).target;
            RecordingBean proxy = context.getBean(RecordingBean.class);
            assertTrue(AopUtils.isCglibProxy(proxy));

            proxy.accept(List.of(1, 2));
            assertTrue(target.deliveries.isEmpty());
            proxy.accept(List.of(3));
            assertEquals(List.of(1, 2, 3), next(target.deliveries).values());
            proxy.accept(List.of(4));
            assertTrue(target.deliveries.isEmpty());
        }
        assertEquals(List.of(4), target.deliveries.remove().values());
    }

    @Test
    void bootAutoConfigurationRegistersAProcessorAndFlushesOnContextClose() {
        RecordingBean target = new RecordingBean();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class))
                .withBean("recordingTarget", RecordingBean.class, () -> target)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(BatchBeanPostProcessor.class).size());
                    RecordingBean proxy = context.getBean(RecordingBean.class);
                    assertTrue(AopUtils.isCglibProxy(proxy));

                    proxy.accept(List.of(1, 2));
                    assertTrue(target.deliveries.isEmpty());
                    proxy.accept(List.of(3));
                    assertEquals(List.of(1, 2, 3), next(target.deliveries).values());
                    proxy.accept(List.of(4));
                    assertTrue(target.deliveries.isEmpty());
                });

        assertEquals(List.of(4), target.deliveries.remove().values());
    }

    @Test
    void bootAutoConfigurationBacksOffWhenACustomProcessorAlreadyExists() {
        BatchBeanPostProcessor customProcessor = new BatchBeanPostProcessor();
        RecordingBean target = new RecordingBean();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class))
                .withBean("customBatchBeanPostProcessor", BatchBeanPostProcessor.class, () -> customProcessor)
                .withBean("recordingTarget", RecordingBean.class, () -> target)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(BatchBeanPostProcessor.class).size());
                    assertSame(customProcessor, context.getBean(BatchBeanPostProcessor.class));
                    assertSame(customProcessor, context.getBean("customBatchBeanPostProcessor"));
                    RecordingBean proxy = context.getBean(RecordingBean.class);
                    assertTrue(AopUtils.isCglibProxy(proxy));

                    proxy.accept(List.of(1, 2, 3));
                    assertEquals(List.of(1, 2, 3), next(target.deliveries).values());
                    proxy.accept(List.of(4));
                });

        assertEquals(List.of(4), target.deliveries.remove().values());
    }

    @Test
    void contextShutdownDeliversPendingDataBeforeAnnotatedBeanIsDestroyed() {
        DisposableRecordingBean target;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(DisposableBatchingConfiguration.class)) {
            target = context.getBean(DisposableBatchingConfiguration.class).target;
            BatchBeanPostProcessor contextProcessor = context.getBean(BatchBeanPostProcessor.class);
            SmartLifecycle lifecycle = assertInstanceOf(SmartLifecycle.class, contextProcessor);
            assertEquals(Integer.MAX_VALUE, lifecycle.getPhase());
            DisposableRecordingBean proxy = context.getBean(DisposableRecordingBean.class);
            assertTrue(AopUtils.isCglibProxy(proxy));

            proxy.accept(List.of(7));
            assertTrue(target.deliveries.isEmpty());
            assertFalse(target.destroyed);
        }

        assertTrue(target.destroyed, "Spring must invoke the annotated bean's destruction callback");
        assertTrue(target.deliveryOccurredBeforeDestroy,
                "Lifecycle shutdown must complete pending deliveries before destroying the target bean");
        assertEquals(List.of(7), target.deliveries.remove().values());
        assertTrue(target.deliveries.isEmpty());
    }

    private <T> T process(Object target, String beanName, Class<T> type) {
        return type.cast(processor.postProcessAfterInitialization(target, beanName));
    }

    private static Delivery next(BlockingQueue<Delivery> deliveries) throws InterruptedException {
        Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivery, "Expected an asynchronous batch delivery");
        return delivery;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test coordination latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting the test coordination latch", exception);
        }
    }

    private record Delivery(List<Integer> values, Thread thread) {
        static Delivery capture(Collection<Integer> values) {
            return new Delivery(new ArrayList<>(values), Thread.currentThread());
        }
    }

    public static class RecordingBean {
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        final AtomicInteger directCalls = new AtomicInteger();
        volatile Thread directThread;

        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 3)
        public void accept(Collection<Integer> values) {
            deliveries.add(Delivery.capture(values));
        }

        public List<Integer> direct(List<Integer> values) {
            directThread = Thread.currentThread();
            directCalls.incrementAndGet();
            return values;
        }
    }

    public static class TimeoutBean {
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

        @Batch(timeout = 120, maxSize = 100)
        public void accept(List<Integer> values) {
            deliveries.add(Delivery.capture(values));
        }
    }

    public static class SecondsTimeoutBean {
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

        @Batch(timeout = 1, timeUnit = TimeUnit.SECONDS, maxSize = 100)
        public void accept(List<Integer> values) {
            deliveries.add(Delivery.capture(values));
        }
    }

    public static class FixedTimeoutBean {
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

        @Batch(timeout = 1_200, maxSize = 100)
        public void accept(List<Integer> values) {
            deliveries.add(Delivery.capture(values));
        }
    }

    public static class DefaultBean {
        @Batch
        public void accept(List<Integer> values) {
        }
    }

    public static class TwoMethodsBean {
        final BlockingQueue<Delivery> firstDeliveries = new LinkedBlockingQueue<>();
        final BlockingQueue<Delivery> secondDeliveries = new LinkedBlockingQueue<>();

        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 3)
        public void first(List<Integer> values) {
            firstDeliveries.add(Delivery.capture(values));
        }

        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 3)
        public void second(Collection<Integer> values) {
            secondDeliveries.add(Delivery.capture(values));
        }
    }

    public static class BlockingBean {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 3)
        public void accept(List<Integer> values) {
            entered.countDown();
            await(release);
            deliveries.add(Delivery.capture(values));
        }
    }

    public static class FailingBean {
        final AtomicInteger calls = new AtomicInteger();
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 1)
        public void accept(List<Integer> values) {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Deliberate asynchronous test failure");
            }
            deliveries.add(Delivery.capture(values));
        }
    }

    public interface BatchApi {
        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 2)
        void accept(List<Integer> values);
    }

    public static class InterfaceBean implements BatchApi {
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

        @Override
        public void accept(List<Integer> values) {
            deliveries.add(Delivery.capture(values));
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class TestConfiguration {
        final RecordingBean target = new RecordingBean();

        @Bean
        public static BatchBeanPostProcessor batchBeanPostProcessor() {
            return new BatchBeanPostProcessor();
        }

        @Bean
        public RecordingBean recordingTarget() {
            return target;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableBatching
    public static class EnabledBatchingConfiguration {
        final RecordingBean target = new RecordingBean();

        @Bean
        public RecordingBean recordingTarget() {
            return target;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableBatching
    public static class DisposableBatchingConfiguration {
        final DisposableRecordingBean target = new DisposableRecordingBean();

        @Bean
        public DisposableRecordingBean disposableRecordingTarget() {
            return target;
        }
    }

    public static class DisposableRecordingBean implements DisposableBean {
        final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        volatile boolean destroyed;
        volatile boolean deliveryOccurredBeforeDestroy;

        @Batch(timeout = 1, timeUnit = TimeUnit.MINUTES, maxSize = 3)
        public void accept(List<Integer> values) {
            deliveries.add(Delivery.capture(values));
        }

        @Override
        public void destroy() {
            deliveryOccurredBeforeDestroy = !deliveries.isEmpty();
            destroyed = true;
        }
    }

    public static class PrivateMethodBean {
        @Batch
        private void accept(List<Integer> values) {
        }
    }

    public static class ProtectedMethodBean {
        @Batch
        protected void accept(List<Integer> values) {
        }
    }

    public static class PackagePrivateMethodBean {
        @Batch
        void accept(List<Integer> values) {
        }
    }

    public static class StaticMethodBean {
        @Batch
        public static void accept(List<Integer> values) {
        }
    }

    public static class FinalMethodBean {
        @Batch
        public final void accept(List<Integer> values) {
        }
    }

    public static class ReturningMethodBean {
        @Batch
        public int accept(List<Integer> values) {
            return values.size();
        }
    }

    public static class NoArgumentsBean {
        @Batch
        public void accept() {
        }
    }

    public static class MultipleArgumentsBean {
        @Batch
        public void accept(List<Integer> values, int extra) {
        }
    }

    public static class ScalarArgumentBean {
        @Batch
        public void accept(Integer value) {
        }
    }

    public static class SetArgumentBean {
        @Batch
        public void accept(Set<Integer> values) {
        }
    }

    public static class ConcreteCollectionBean {
        @Batch
        public void accept(ArrayList<Integer> values) {
        }
    }

    public static class ZeroSizeBean {
        @Batch(maxSize = 0)
        public void accept(List<Integer> values) {
        }
    }

    public static class NegativeSizeBean {
        @Batch(maxSize = -1)
        public void accept(List<Integer> values) {
        }
    }

    public static class ZeroTimeoutBean {
        @Batch(timeout = 0)
        public void accept(List<Integer> values) {
        }
    }

    public static class NegativeTimeoutBean {
        @Batch(timeout = -1)
        public void accept(List<Integer> values) {
        }
    }
}
