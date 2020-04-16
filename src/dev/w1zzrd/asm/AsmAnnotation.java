package dev.w1zzrd.asm;

import com.sun.istack.internal.Nullable;

import java.util.Map;

public final class AsmAnnotation {
    private final String annotationType;
    private final Map<String, Object> entries;

    public AsmAnnotation(String annotationType, Map<String, Object> entries) {
        this.annotationType = annotationType;
        this.entries = entries;
    }

    public String getAnnotationType() {
        return annotationType;
    }

    @Nullable
    public <T> T getEntry(String name, Class<T> type) {
        return hasEntry(name) ? (T)entries.get(name) : null;
    }

    public boolean hasEntry(String name) {
        return entries.containsKey(name);
    }
}
