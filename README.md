# Batcher

Two APIs are available:

- **Generated inputs:** `Batcher.init(interface, settings, handler)` collects single elements
  of any reference type without Spring, AOP, or reflection on the input path.
- **Spring batching:** the existing `@Batch` API combines collection elements from calls to
  Spring beans. Buffers are stored in Caffeine and calls are intercepted by Spring AOP proxies.

## Installation

Build and install the library locally with `./mvnw clean install` (Java 27 required).
Add the dependency to your Maven project:

```xml
<dependency>
    <groupId>org.batcher</groupId>
    <artifactId>batcher</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Generated Inputs

Declare the input interface in its own source file. `User` is your application's DTO, record,
entity, or any other reference type:

```java
import org.batcher.BatchInput;
import org.batcher.BatchInputType;

@BatchInputType
public interface UserInput extends BatchInput<User> {
    void foo(User value);
}
```

Pass batching settings and the batch handler when creating the implementation:

```java
import java.time.Duration;
import org.batcher.Batcher;
import org.batcher.BatchSettings;

try (var b = Batcher.init(
        UserInput.class,
        new BatchSettings(100, Duration.ofMillis(200)),
        repository::saveAll)) {
    b.foo(firstUser);
    b.foo(secondUser);
} // Flushes the remaining elements and waits for delivery.
```

The handler receives `List<User>`; `BatchInput<User>` links the interface and handler types
at compile time. No service class, `default` batch method, or `@Batch` annotation is needed.
Objects are stored by reference, not cloned or wrapped. Creating an object before calling
`foo()` is the caller's responsibility; the batcher does not create an object per addition.

Payload types may also be interfaces, arrays, or parameterized types such as `Envelope<User>`,
`Map<String, User>`, and `List<? extends User>`. To mix unrelated payload types, declare
`BatchInput<Object>` with `void foo(Object value)`; the handler then receives `List<Object>`.

### Enable Code Generation

On modern JDKs, annotation processing must be explicitly enabled. In the **consumer project's**
Maven compiler configuration, add the library to the processor path and select the processor
(keep the regular library dependency as well):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.15.0</version>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.batcher</groupId>
                <artifactId>batcher</artifactId>
                <version>0.0.1-SNAPSHOT</version>
            </path>
        </annotationProcessorPaths>
        <annotationProcessors>
            <annotationProcessor>org.batcher.processor.BatchInputProcessor</annotationProcessor>
        </annotationProcessors>
    </configuration>
</plugin>
```

The processor generates `UserInputImpl`. `Batcher.init()` finds it once, via reflection at
initialization; `foo(User)` calls its typed buffer directly. This project's main compilation disables
processing because it builds the processor itself; test compilation enables the built processor.

### Generated Input Contract

- A public, non-generic, non-sealed interface annotated with `@BatchInputType` must extend
  `BatchInput<T>` with a fully resolved element type. Nested interfaces are supported when all
  enclosing types are public.
- Exactly one abstract input method is supported besides `close()`: it must return `void`, take
  one argument and declare no checked exceptions. Inherited input methods and additional
  default/static methods are supported. Generated `close()` always performs buffer cleanup,
  even if the interface declares a default `close()`.
- Reference arguments must exactly match `T`; any object assignable to that type can be
  submitted. Nulls and object identity are preserved. Parameterized types, arrays, and wildcard
  type arguments with resolved bounds are supported. Raw types and unbound type variables
  are not supported.
- As an optional primitive optimization, `int` is supported for `BatchInput<Integer>` using
  an `int[]` buffer; other scalar primitives are not supported yet. Object inputs do not
  depend on this specialization.
- `maxSize` and `timeout` must be positive. Timeout is measured from the first element, not
  reset by subsequent additions; it determines when a batch is queued, not when it completes.
- Concurrent additions are serialized by the buffer monitor. Order and duplicates are
  preserved; for concurrent callers, order follows monitor acquisition.
- Each initialized input owns independent scheduling and sequential asynchronous delivery,
  with up to one timer thread and one worker thread, started lazily. Always close inputs or
  arrange for application lifecycle cleanup. Calling-thread context is not propagated.
- `close()` rejects further additions, queues the remainder and waits up to 30 seconds for
  deliveries. Timeout/interruption raises `IllegalStateException`; already queued work is not
  cancelled. Closing from the handler itself initiates shutdown without waiting for that worker.
- Handler failures are logged, later deliveries continue, and there are no retries.
- Allocations remain **per batch**: storage, timer/queue tasks and the delivered list. The `int`
  path boxes values when the worker creates `List<Integer>`. Delivered lists are not reused and
  may be retained by the handler. Reference objects themselves are not copied.
- Storage is allocated with capacity `maxSize` on the first addition to each batch. Queued work
  is unbounded: choose sensible limits and ensure producers do not permanently outpace handlers.
  Delivery is best-effort and in-memory, not durable.

## Spring Integration (Existing API)

Spring Boot registers the post processor automatically through auto-configuration.
For a plain Spring context, add `@EnableBatching` to your configuration:

```java
@Configuration
@EnableBatching
class ApplicationConfiguration {
}
```

## Usage

```java
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.batcher.Batch;
import org.springframework.stereotype.Service;

@Service
public class NumberService {

    @Batch(timeout = 200, maxSize = 100, timeUnit = TimeUnit.MILLISECONDS)
    public void foo(Collection<Integer> numbers) {
        // Each call receives up to 100 elements combined from multiple input collections.
        System.out.println(numbers);
    }
}
```

Calls to `numberService.foo(List.of(1, 2))` and `numberService.foo(List.of(3))`
are combined into `foo([1, 2, 3])` when the timeout expires. Once `maxSize` is reached,
the full batch is queued for execution immediately.

- `timeout`: the wait time from the **first element** of a batch. Subsequent calls do not extend it.
- `maxSize`: the maximum number of **elements**, not calls. Larger collections are split into batches.
- Defaults: `timeout = 100`, `maxSize = 100`, `timeUnit = MILLISECONDS`.
- Element order and duplicates are preserved. For concurrent calls, order is determined
  by buffer lock acquisition; elements from different calls are not interleaved.
- Each bean instance and method has an independent buffer.
- The input collection is copied before the call returns; the element objects themselves are not copied.
- Empty collections are ignored; `null` causes an `IllegalArgumentException`.
- The original method is invoked **asynchronously**, with deliveries executed sequentially
  on a single worker thread. A separate timer thread is not blocked by slow methods.
  The timeout determines when a batch is queued, not when it finishes if the worker is busy.
- Remaining batches are flushed when the context closes, with up to 30 seconds allowed for completion.
  Delivery failures are logged, subsequent batches continue to execute, and there are no automatic retries.

## Contract and Limitations

The method must be `public`, neither `static` nor `final`, return `void`, and accept
exactly one parameter declared as `Collection<T>` or `List<T>`. `Set` and concrete collection
types are not supported: they may alter ordering or duplicates, or reject the supplied `ArrayList`.
Invalid signatures and nonpositive settings are rejected when the bean is created.
The annotation may be placed on the implementation method or an interface method.

A class-based (CGLIB) proxy is normally created, so the class must not be `final`.
If the bean is already a Spring JDK proxy, interface-based access is retained;
the batched method must be exposed through its interface. Deliveries pass through
the existing proxy, so its advice is applied to each batch delivery.

Calls must go through the Spring proxy: `new NumberService()` and `this.foo(...)`
bypass batching. The calling thread's context, such as a transaction, security context,
or MDC, is not automatically propagated to the worker.

Delivery is best-effort and in-memory: data may be lost if the process terminates unexpectedly,
and target method failures are not returned to the caller. Buffer cache entries are deliberately
retained until the context closes to avoid losing pending elements. The delivery queue is unbounded:
an unlimited stream of prototype beans, or producers that outpace the consumer, requires a separate
memory limit or backpressure strategy. For guaranteed delivery, use a durable queue or an outbox.

The artifact is built as a regular JAR, not an executable Boot JAR, and can be used as a Maven
dependency. Spring Boot and Java versions match the original project: 4.1.1 and 27, respectively.

## Testing

```sh
./mvnw test
```
