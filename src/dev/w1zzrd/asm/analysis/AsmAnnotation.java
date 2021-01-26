package dev.w1zzrd.asm.analysis;

import dev.w1zzrd.asm.exception.AnnotationMismatchException;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import sun.reflect.annotation.AnnotationParser;
import sun.reflect.annotation.AnnotationType;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Java ASM annotation data representation
 * @param <A> Type of the annotation
 */
public final class AsmAnnotation<A extends Annotation> {
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
        if (hasExplicitEntry(name))
            return (T)entries.get(name);
        return (T)getValueMethod(name).getDefaultValue();
    }

    public <T> T getEntryOr(String name, T defVal) {
        if (!hasExplicitEntry(name))
            return defVal;

        return getEntry(name);
    }

    public <T extends Enum<T>> T getEnumEntry(String entryName) {
        if (!hasExplicitEntry(entryName)) {
            if (hasDefaultEntry(entryName))
                return (T)getValueMethod(entryName).getDefaultValue();

            throw new IllegalArgumentException(String.format("No entry \"%s\" in annotation!", entryName));
        }

        final String[] value = getEntry(entryName);
        final String typeName = value[0];
        final String enumName = value[1];

        Class<T> type;
        try {
            type = (Class<T>) Class.forName(typeName.substring(1, typeName.length() - 1).replace('/', '.'));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        try {
            T[] values = (T[]) type.getDeclaredMethod("values").invoke(null);

            for (T declaredValue : values)
                if (declaredValue.name().equals(enumName))
                    return declaredValue;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        throw new AnnotationMismatchException(String.format(
                "Could not find an enum of type %s with name \"%s\"",
                typeName,
                enumName
        ));
    }

    protected Method getValueMethod(String name) {
        for (Method m : annotationType.getDeclaredMethods())
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }

        return null;
    }

    protected boolean hasDefaultEntry(String name) {
        return getValueMethod(name) != null;
    }

    protected boolean hasExplicitEntry(String name) {
        return entries.containsKey(name);
    }

    public boolean hasEntry(String name) {
        return hasDefaultEntry(name) || hasExplicitEntry(name);
    }


    public static <T extends Annotation> AsmAnnotation<T> getAnnotation(AnnotationNode node) {
        Class<T> cls;
        try {
            cls = (Class<T>) Class.forName(node.desc.substring(1, node.desc.length() - 1).replace('/', '.'));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        HashMap<String, Object> entries = new HashMap<>();
        if (node.values != null)
            for (int i = 0; i < node.values.size(); i += 2)
                entries.put((String)node.values.get(i), node.values.get(i + 1));

        return new AsmAnnotation<T>(cls, entries);
    }
}
