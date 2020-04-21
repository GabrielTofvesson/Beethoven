package dev.w1zzrd.asm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a class for injection into a given target class
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface InjectClass {
    /**
     * Class to inject into
     * @return Type of the target class
     */
    Class<?> value();

    /**
     * Whether or not to inject interfaces in injection class into target class
     * @return True if interfaces should be injected, else false
     */
    boolean injectInterfaces() default true;
}
