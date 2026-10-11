package org.batcher;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/** Combines collection elements into asynchronous, size- or timeout-triggered calls. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Batch {

    /** Maximum wait from the first element of a new batch. */
    long timeout() default 100;

    /** Maximum number of elements passed to the target method in one call. */
    int maxSize() default 100;

    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
}
