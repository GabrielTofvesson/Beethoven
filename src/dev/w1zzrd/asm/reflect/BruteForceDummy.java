package dev.w1zzrd.asm.reflect;

/**
 * Dummy class used as a canary for brute-forcing the object field offset
 * of AccessibleObject override flag.
 */
public class BruteForceDummy {
    private final int inaccessible = 0;
}
