package dev.w1zzrd.asm;

/**
 * How to inject a method
 */
public enum InPlaceInjection {
    /**
     * Before existing method instructions. Return value is ignored
     */
    BEFORE,

    /**
     * After existing method instructions. Return values from previous instructions can be accepted
     */
    AFTER,

    /**
     * Replace method instructions in target method
     */
    REPLACE
}
