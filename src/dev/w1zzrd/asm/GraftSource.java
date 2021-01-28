package dev.w1zzrd.asm;

import dev.w1zzrd.asm.analysis.AsmAnnotation;
import dev.w1zzrd.asm.signature.MethodSignature;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public final class GraftSource {
    private final String typeName;
    private final HashMap<MethodNode, List<AsmAnnotation<?>>> methodAnnotations;
    private final HashMap<FieldNode, List<AsmAnnotation<?>>> fieldAnnotations;
    private final ClassNode source;

    public GraftSource(ClassNode source) {
        this.source = source;
        this.typeName = source.name;

        methodAnnotations = new HashMap<>();
        for (MethodNode mNode : source.methods)
        {
            List<AsmAnnotation<?>> annotations = parseAnnotations(mNode.visibleAnnotations);
            if (hasNoInjectionDirective(annotations))
                continue;

            methodAnnotations.put(mNode, annotations);
        }

        fieldAnnotations = new HashMap<>();
        for (FieldNode fNode : source.fields)
        {
            List<AsmAnnotation<?>> annotations = parseAnnotations(fNode.visibleAnnotations);
            if (hasNoInjectionDirective(annotations))
                continue;

            fieldAnnotations.put(fNode, annotations);
        }
    }

    public MethodNode getMethodNode(String name, String desc) {
        for (MethodNode node : source.methods)
            if (node.name.equals(name) && node.desc.equals(desc))
                return node;

        return null;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getMethodTarget(MethodNode node) {
        if (methodAnnotations.containsKey(node)) {
            String target = getInjectionDirective(methodAnnotations.get(node)).getEntry("target");
            if (target != null && target.length() != 0)
                return target;
        }

        return node.name + node.desc;
    }

    public String getMethodTargetName(MethodNode node) {
        String target = getMethodTarget(node);

        if (target.indexOf("(") > 0)
            return target.substring(0, target.indexOf('('));

        return node.name;
    }

    public MethodSignature getMethodTargetSignature(MethodNode node, boolean acceptOriginalReturn) {
        String target = getMethodTarget(node);

        if (target.contains("("))
            return new MethodSignature(target.substring(target.indexOf('(')));

        MethodSignature sig = new MethodSignature(node.desc);
        return acceptOriginalReturn ? sig.withoutLastArg() : sig;
    }

    public boolean isMethodInjected(String name, String desc) {
        return getInjectedMethod(name, desc) != null;
    }

    public @Nullable MethodNode getInjectedMethod(String name, String desc) {
        return methodAnnotations
                .entrySet()
                .stream()
                .filter(kv ->
                        {
                            if (!kv.getKey().name.equals(name)) return false;
                            assert getInjectionDirective(kv.getValue()) != null;
                            return new MethodSignature(getInjectionDirective(kv.getValue())
                                    .getEntryOr("target", kv.getKey().desc))
                                    .toString()
                                    .equals(desc);
                        }
                )
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public String getMethodTargetName(String name, String desc) {
        final MethodNode inject = getInjectedMethod(name, desc);
        return inject == null ? name : getMethodTargetName(inject);
    }

    public String getFieldTargetName(FieldNode node) {
        if (fieldAnnotations.containsKey(node)) {
            String target = getInjectionDirective(fieldAnnotations.get(node)).getEntry("target");
            if (target != null && target.length() != 0)
                return target;
        }

        return node.name;
    }

    public List<AsmAnnotation<?>> getMethodAnnotations(MethodNode node) {
        return methodAnnotations.get(node);
    }

    public List<AsmAnnotation<?>> getFieldAnnotations(FieldNode node) {
        return fieldAnnotations.get(node);
    }

    public List<MethodNode> getInjectMethods() {
        return methodAnnotations
                .keySet()
                .stream()
                .sorted(Comparator.comparingInt(a -> getMethodInjectAnnotation(a).getEntry("priority")))
                .collect(Collectors.toList());
    }

    public List<FieldNode> getInjectFields() {
        return fieldAnnotations
                .keySet()
                .stream()
                .sorted(Comparator.comparingInt(a -> getFieldInjectAnnotation(a).getEntry("priority")))
                .collect(Collectors.toList());
    }

    public AsmAnnotation<Inject> getMethodInjectAnnotation(MethodNode node) {
        return getInjectionDirective(methodAnnotations.get(node));
    }

    public AsmAnnotation<Inject> getFieldInjectAnnotation(FieldNode node) {
        return getInjectionDirective(fieldAnnotations.get(node));
    }

    private static boolean hasNoInjectionDirective(List<AsmAnnotation<?>> annotations) {
        return getInjectionDirective(annotations) == null;
    }

    private static AsmAnnotation<Inject> getInjectionDirective(List<AsmAnnotation<?>> annotations) {
        for (AsmAnnotation<?> annot : annotations)
            if (annot.getAnnotationType() == Inject.class)
                return (AsmAnnotation<Inject>) annot;

        return null;
    }

    private static List<AsmAnnotation<?>> parseAnnotations(List<AnnotationNode> annotations) {
        return annotations == null ? new ArrayList<>() : annotations.stream().map(AsmAnnotation::getAnnotation).collect(Collectors.toList());
    }
}
