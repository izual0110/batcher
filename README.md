# Spring Batcher

This library combines collection elements from multiple calls to a Spring bean into batches.
Buffers are stored in **Caffeine**, annotations are discovered by `BatchBeanPostProcessor`,
and calls are intercepted by Spring AOP proxies.

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
