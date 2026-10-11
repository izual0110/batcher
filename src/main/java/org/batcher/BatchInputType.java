package org.batcher;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates an implementation of a public interface extending {@link BatchInput}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface BatchInputType {
}
