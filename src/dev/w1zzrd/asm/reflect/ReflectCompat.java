package dev.w1zzrd.asm.reflect;

import dev.w1zzrd.asm.reflect.BruteForceDummy;
import sun.misc.Unsafe;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;

/**
 * Internal compatibility layer for Java 8+
 */
final class ReflectCompat {
    private static final Unsafe theUnsafe;

    //private static final Field accessibleObject_field_override;
    private static final long accessibleObject_fieldOffset_override;


    static {
        long accessibleObject_fieldOffset_override1;
        theUnsafe = guarantee(() -> {
            Field f1 = Unsafe.class.getDeclaredField("theUnsafe");
            f1.setAccessible(true);
            return (Unsafe) f1.get(null);
        });

        // Field guaranteed to fail
        AccessibleObject f = guarantee(() -> BruteForceDummy.class.getDeclaredField("inaccessible"));
        BruteForceDummy dummy = new BruteForceDummy();
        try {
            // Well-defined solution for finding object field offset
            accessibleObject_fieldOffset_override1 = theUnsafe.objectFieldOffset(AccessibleObject.class.getDeclaredField("override"));
        } catch (NoSuchFieldException e) {
            // Brute-force solution when VM hides fields based on exports
            long offset = 0;
            int temp;

            // Booleans are usually 32 bits large so just search in 4-byte increments
            while (true) {
                temp = theUnsafe.getInt(f, offset);

                // Ensure we're probably working with a false-value
                if (temp == 0) {
                    theUnsafe.putBoolean(f, offset, true);
                    boolean fails = fails(() -> ((Field)f).get(dummy));

                    theUnsafe.putInt(f, offset, temp);

                    if (!fails)
                        break;
                }

                offset += 4;
            }

            accessibleObject_fieldOffset_override1 = offset;
        }
        accessibleObject_fieldOffset_override = accessibleObject_fieldOffset_override1;
    }

    /**
     * Forcefully set the override flag of an {@link AccessibleObject}
     * @param obj Object to override
     * @param access Value of flag
     */
    static void setAccessible(AccessibleObject obj, boolean access) {
        theUnsafe.putBoolean(obj, accessibleObject_fieldOffset_override, access);
    }





    private interface ExceptionRunnable<T> {
        T run() throws Throwable;
    }

    /**
     * Convenience method for ignoring exceptions where they're guaranteed not to be thrown.
     * @param run Interface describing action to be performed
     * @param <T> Return type expected from call to {@link ExceptionRunnable#run()}
     * @return Expected value
     */
    private static <T> T guarantee(ExceptionRunnable<T> run) {
        try {
            return run.run();
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    /**
     * Check if a given code block fails to run all the way through.
     * @param run {@link ExceptionRunnable} to check
     * @return True if an exception was thrown, otherwise false
     */
    private static boolean fails(ExceptionRunnable<?> run) {
        try {
            run.run();
        } catch (Throwable t) {
            return true;
        }
        return false;
    }
}
