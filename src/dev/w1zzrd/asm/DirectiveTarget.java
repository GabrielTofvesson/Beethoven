package dev.w1zzrd.asm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface DirectiveTarget {

    TargetType value();

    enum TargetType {
        CALL_ORIGINAL, CALL_SUPER;
    }
}
