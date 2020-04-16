package dev.w1zzrd.asm;

import com.sun.istack.internal.Nullable;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class Merger {

    protected final ClassNode targetNode;
    protected final List<MethodNode> inject = new ArrayList<>();


    public Merger(String targetClass) throws IOException {
        this(targetClass, ClassLoader.getSystemClassLoader());
    }

    public Merger(String targetClass, ClassLoader loader) throws IOException {
        this(getClassNode(targetClass, loader));
    }

    public Merger(byte[] data) {
        this(readClass(data));
    }

    public Merger(ClassNode targetNode) {
        this.targetNode = targetNode;
    }


    public String getTargetName() {
        return targetNode.name;
    }

    public void inject(MethodNode inject, String injectOwner) {
        transformInjection(inject, injectOwner);
        this.inject.add(inject);
    }

    public void inject(ClassNode inject) {
        inject.methods.stream().filter(Merger::shouldInject).forEach(mNode -> inject(mNode, inject.name));

        if (inject.visibleAnnotations != null && inject.interfaces != null) {

            AsmAnnotation annot = getAnnotation("Ldev/w1zzrd/asm/InjectClass;", inject);

            // If there is not inject annotation or there is an
            // explicit request to not inject interfaces, just return
            if (annot == null || (annot.hasEntry("injectInterfaces") && !annot.getEntry("injectInterfaces", Boolean.class)))
                return;


            if (targetNode.interfaces == null)
                targetNode.interfaces = new ArrayList<>();

            for (String iface : inject.interfaces)
                if (!targetNode.interfaces.contains(iface))
                    targetNode.interfaces.add(iface);
        }
    }

    protected String resolveField(String fieldName) {
        for(FieldNode fNode : targetNode.fields)
            if (fNode.name.equals(fieldName))
                return fNode.desc;

        throw new RuntimeException(String.format("There is no field \"%s\" in %s", fieldName, getTargetName()));
    }

    protected void transformInjection(MethodNode inject, String injectOwner) {
        ArrayList<AbstractInsnNode> instr = new ArrayList<>();

        for (int i = 0; i < inject.instructions.size(); ++i) {
            AbstractInsnNode node = inject.instructions.get(i);
            if (!(node instanceof LineNumberNode)) {
                if (node instanceof MethodInsnNode && ((MethodInsnNode) node).owner.equals("dev/w1zzrd/asm/Merger") && ((MethodInsnNode) node).name.equals("field")) {
                    // field access
                    AbstractInsnNode loadNode = instr.get(instr.size() - 1);
                    if(loadNode instanceof LdcInsnNode) {
                        instr.remove(instr.size() - 1);

                        String constant = (String) ((LdcInsnNode) loadNode).cst;

                        instr.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        instr.add(new FieldInsnNode(Opcodes.GETFIELD, getTargetName(), constant, resolveField(constant)));
                    }
                } else {
                    // Attempt to fix injector ownership
                    for(Field f : node.getClass().getFields()) {
                        try {
                            if (f.getName().equals("owner") && f.getType().equals(String.class) && f.get(node).equals(injectOwner))
                                f.set(node, getTargetName());
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                    }

                    instr.add(node);
                }
            }
        }

        InsnList collect = new InsnList();
        for(AbstractInsnNode node : instr)
            collect.add(node);

        inject.instructions = collect;
    }

    public boolean shouldInject(ClassNode inject) {
        if (inject.visibleAnnotations != null) {
            for (AnnotationNode aNode : inject.visibleAnnotations)
                if (
                        aNode.desc.equals("Ldev/w1zzrd/asm/InjectClass;") &&
                                aNode.values.indexOf("value") != -1 &&
                                ((Type) aNode.values.get(aNode.values.indexOf("value") + 1)).getClassName().equals(getTargetName())
                        )
                    return true;
        }

        return false;
    }

    public byte[] toByteArray() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        List<MethodNode> original = targetNode.methods;
        targetNode.methods = targetNode.methods.stream().filter(this::isNotInjected).collect(Collectors.toList());
        targetNode.accept(writer);
        targetNode.methods = original;

        inject.forEach(node -> node.accept(writer));

        return writer.toByteArray();
    }

    public Class<?> compile() {
        return compile(ClassLoader.getSystemClassLoader());
    }

    public Class<?> compile(ClassLoader loader) {
        Method m = null;
        try {
            m = ClassLoader.class.getDeclaredMethod("defineClass", byte[].class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }

        assert m != null;
        m.setAccessible(true);

        byte[] data = toByteArray();

        try {
            return (Class<?>) m.invoke(loader, data, 0, data.length);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }


    protected boolean isNotInjected(MethodNode node) {
        for (MethodNode mNode : inject)
            if (methodNodeEquals(node, mNode))
                return false;

        return true;
    }



    // To be used instead of referencing object constructs
    public static Object field(String name) {
        throw new RuntimeException("Field not injected");
    }

    // TODO: Implement
    public static Object method(String name, Object... args) {
        throw new RuntimeException("Method not injected");
    }





    @Nullable
    protected static AsmAnnotation getAnnotation(String annotationType, ClassNode cNode) {
        for (AnnotationNode aNode : cNode.visibleAnnotations)
            if (aNode.desc.equals(annotationType)) {
                HashMap<String, Object> map = new HashMap<>();

                // Collect annotation values
                if (aNode.values != null)
                    for (int i = 1; i < aNode.values.size(); i+=2)
                        map.put((String)aNode.values.get(i - 1), aNode.values.get(i));

                return new AsmAnnotation(annotationType, map);
            }

        return null;
    }

    protected static boolean methodNodeEquals(MethodNode a, MethodNode b) {
        return a.name.equals(b.name) && Objects.equals(a.signature, b.signature);
    }

    protected static boolean shouldInject(MethodNode node) {
        if (node.visibleAnnotations == null) return false;
        for (AnnotationNode aNode : node.visibleAnnotations)
            if (aNode.desc.equals("Ldev/w1zzrd/asm/Inject;"))
                return true;

        return false;
    }

    public static ClassNode getClassNode(URL url) throws IOException {
        return readClass(getClassBytes(url));
    }

    public static ClassNode readClass(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, 0);
        return node;
    }

    public static ClassNode getClassNode(String name) throws IOException {
        return readClass(getClassBytes(name));
    }

    public static ClassNode getClassNode(String name, ClassLoader loader) throws IOException {
        return readClass(getClassBytes(name, loader));
    }

    public static byte[] getClassBytes(String name) throws IOException {
        return getClassBytes(name, ClassLoader.getSystemClassLoader());
    }

    public static byte[] getClassBytes(String name, ClassLoader loader) throws IOException {
        return getClassBytes(Objects.requireNonNull(loader.getResource(name.replace('.', '/') + ".class")));
    }

    public static byte[] getClassBytes(URL url) throws IOException {
        InputStream stream = url.openStream();
        byte[] classData = new byte[stream.available()];

        int total = 0;
        do total += stream.read(classData, total, classData.length - total);
        while (total < classData.length);

        return classData;
    }
}
