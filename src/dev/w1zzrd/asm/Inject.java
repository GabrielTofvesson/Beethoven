package dev.w1zzrd.asm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import static dev.w1zzrd.asm.InPlaceInjection.INJECT;

/**
 * Mark a field or method for injection into a target
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
public @interface Inject {
    /**
     * How to inject the method. Note: not valid for fields
     * @return {@link InPlaceInjection}
     */
    InPlaceInjection value() default INJECT;

    /**
     * Explicit method target signature. Note: not valid for fields
     * @return Target signature
     */
    String target() default "";

    /**
     * Whether or not to accept the return value from the method being injected into.
     * Note: Only valid if {@link #value()} is {@link InPlaceInjection#AFTER}
     * @return True if the injection method should receive the return value
     */
    boolean acceptOriginalReturn() default false;

    int priority() default Integer.MIN_VALUE;
}
