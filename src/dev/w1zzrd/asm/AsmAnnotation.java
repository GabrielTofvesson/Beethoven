package dev.w1zzrd.asm;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Java ASM annotation data representation
 * @param <A> Type of the annotation
 */
final class AsmAnnotation<A extends Annotation> {
    private final Class<A> annotationType;
    private final Map<String, Object> entries;

    public AsmAnnotation(Class<A> annotationType, Map<String, Object> entries) {
        this.annotationType = annotationType;
        this.entries = entries;
    }

    public Class<A> getAnnotationType() {
        return annotationType;
    }

    public <T> T getEntry(String name) {
        if (!hasEntry(name))
            throw new IllegalArgumentException(String.format("No entry \"%s\" in asm annotation!", name));
        return (T)entries.get(name);
    }

    public boolean hasEntry(String name) {
        return entries.containsKey(name);
    }
}
