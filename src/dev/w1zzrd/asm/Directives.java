package dev.w1zzrd.asm;

import dev.w1zzrd.asm.exception.DirectiveNotImplementedException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Directives {

    @DirectiveTarget(DirectiveTarget.TargetType.CALL_ORIGINAL)
    public static void callOriginal() {
        throw new DirectiveNotImplementedException("callOriginal");
    }

    @DirectiveTarget(DirectiveTarget.TargetType.CALL_SUPER)
    public static void callSuper() {
        throw new DirectiveNotImplementedException("callSuper");
    }


    static String directiveNameByTarget(DirectiveTarget.TargetType type) {
        DirectiveTarget target;
        for (Method f : Directives.class.getMethods())
            if ((target = f.getDeclaredAnnotation(DirectiveTarget.class)) != null && target.value().equals(type))
                return f.getName();

        // This won't happen unless I'm dumb, so call it 50/50 odds
        throw new RuntimeException("Could not find implementation of directive target: "+type.name());
    }
}
