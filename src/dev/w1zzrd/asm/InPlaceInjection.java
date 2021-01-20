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
    REPLACE,

    /**
     * Insert method into class. This does not allow overwrites
     */
    INSERT,

    /**
     * Inserts a method if it does not exist in the class, otherwise replace it
     */
    INJECT
}
