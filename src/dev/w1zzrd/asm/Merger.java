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

import static dev.w1zzrd.asm.Merger.SpecialCall.FIELD;
import static dev.w1zzrd.asm.Merger.SpecialCall.METHOD;
import static dev.w1zzrd.asm.Merger.SpecialCall.SUPER;
import static jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

public class Merger {

    protected final ClassNode targetNode;
    //protected final List<MethodNode> injectMethods = new ArrayList<>();
    //protected final List<FieldNode> injectFields = new ArrayList<>();


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

    public String getTargetSuperName() { return targetNode.superName; }

    public void inject(MethodNode inject, String injectOwner) {
        transformInjection(inject, injectOwner);

        targetNode
                .methods
                .stream()
                .filter(it -> methodNodeEquals(it, inject))
                .findFirst()
                .ifPresent(targetNode.methods::remove);

        targetNode.methods.add(inject);
    }

    public void inject(FieldNode inject) {
        targetNode
                .fields
                .stream()
                .filter(it -> fieldNodeEquals(it, inject))
                .findFirst()
                .ifPresent(targetNode.fields::remove);

        targetNode.fields.add(inject);
    }

    public void inject(String className, ClassLoader loader) throws IOException {
        inject(getClassNode(loader.getResource(className.replace('.', '/')+".class")));
    }

    public void inject(String className) throws IOException {
        inject(className, ClassLoader.getSystemClassLoader());
    }

    public void inject(ClassNode inject) {
        inject.methods.stream().filter(Merger::shouldInject).forEach(mNode -> inject(mNode, inject.name));
        inject.fields.stream().filter(Merger::shouldInject).forEach(this::inject);

        if (inject.visibleAnnotations != null && inject.interfaces != null) {

            AsmAnnotation annot = getAnnotation("Ldev/w1zzrd/asm/InjectClass;", inject);

            // If there is not injectMethods annotation or there is an
            // explicit request to not injectMethods interfaces, just return
            if (annot == null || (annot.hasEntry("injectInterfaces") && !annot.getEntry("injectInterfaces", Boolean.class)))
                return;


            if (targetNode.interfaces == null)
                targetNode.interfaces = new ArrayList<>();

            for (String iface : inject.interfaces)
                if (!targetNode.interfaces.contains(iface))
                    targetNode.interfaces.add(iface);
        }
    }

    public void inject(Class<?> inject) throws IOException {
        inject(getClassNode(inject.getResource(inject.getSimpleName()+".class")));
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
                SpecialCall call = node instanceof MethodInsnNode ? getSpecialCall((MethodInsnNode) node) : null;
                if (call != null) {
                    switch (call) {
                        case FIELD: {
                            // field access
                            AbstractInsnNode loadNode = instr.remove(instr.size() - 1);

                            String constant = (String) ((LdcInsnNode) loadNode).cst;

                            instr.add(new VarInsnNode(Opcodes.ALOAD, 0));
                            instr.add(new FieldInsnNode(Opcodes.GETFIELD, getTargetName(), constant, resolveField(constant)));
                            break;
                        }

                        case SUPER: {
                            // super call
                            AbstractInsnNode loadNode = instr.remove(instr.size() - 1);

                            do {
                                node = inject.instructions.get(++i);
                                if (!(node instanceof MethodInsnNode && ((MethodInsnNode) node).name.equals(((LdcInsnNode)loadNode).cst) && ((MethodInsnNode) node).owner.equals(getTargetName())))
                                    instr.add(node);
                                else break;
                            } while(true);

                            ((MethodInsnNode) node).owner = getTargetSuperName();
                            instr.add(node);

                            break;
                        }
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

                    if (node instanceof FrameNode) {
                        if (((FrameNode) node).local != null)
                            ((FrameNode) node).local = ((FrameNode) node).local.stream().map(it -> Objects.equals(it, injectOwner) ? getTargetName() : it).collect(Collectors.toList());

                        if (((FrameNode) node).stack != null)
                            ((FrameNode) node).stack = ((FrameNode) node).stack.stream().map(it -> Objects.equals(it, injectOwner) ? getTargetName() : it).collect(Collectors.toList());
                    }

                    instr.add(node);
                }
            }
        }


        AsmAnnotation annotation = getAnnotation("Ldev/w1zzrd/asm/Inject;", inject);
        if (annotation != null && annotation.hasEntry("value")) {

            InPlaceInjection injection = Objects.requireNonNull(annotation.getEntry("value", InPlaceInjection.class));
            Optional<MethodNode> adapt = targetNode.methods.stream().filter(it -> methodNodeEquals(it, inject)).findFirst();

            if (injection != InPlaceInjection.REPLACE && adapt.isPresent()) {
                ArrayList<AbstractInsnNode> toAdapt = new ArrayList<>();
                adapt.get().instructions.iterator().forEachRemaining(toAdapt::add);

                switch (injection) {
                    case BEFORE: {
                        LabelNode next;
                        boolean created = false;
                        if (toAdapt.size() > 0 && toAdapt.get(0) instanceof LabelNode)
                            next = (LabelNode)toAdapt.get(0);
                        else {
                            next = new LabelNode();
                            toAdapt.add(0, next);
                            created = true;
                        }

                        // If no goto instructions were added, just remove the added label
                        if (removeReturn(instr, next) && created)
                            toAdapt.remove(next);
                        else // A goto call was added. Make sure we inform the JVM of stack and locals with a frame
                            toAdapt.add(1, new FrameNode(Opcodes.F_SAME, -1, null, -1, null));

                        instr.addAll(toAdapt);
                        break;
                    }

                    case AFTER: {
                        LabelNode next;
                        boolean created = false;
                        if (toAdapt.size() > 0 && instr.get(0) instanceof LabelNode)
                            next = (LabelNode)instr.get(0);
                        else {
                            next = new LabelNode();
                            instr.add(0, next);
                            created = true;
                        }

                        // If no goto instructions were added, just remove the added label
                        if (removeReturn(toAdapt, next) && created)
                            instr.remove(next);
                        else // A goto call was added. Make sure we inform the JVM of stack and locals with a frame
                            instr.add(1, new FrameNode(Opcodes.F_SAME, -1, null, -1, null));

                        instr.addAll(0, toAdapt);
                        break;
                    }
                }
            }
        }

        InsnList collect = new InsnList();
        for(AbstractInsnNode node : instr)
            collect.add(node);

        inject.instructions = collect;

        inject.localVariables.forEach(var -> {
            if (var.desc.equals("L"+injectOwner+";"))
                var.desc = "L"+getTargetName()+";";
        });
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
        return toByteArray(COMPUTE_MAXS);
    }

    public byte[] toByteArray(int writerFlags) {
        ClassWriter writer = new ClassWriter(writerFlags);

        // Adapt nodes as necessary
        //List<MethodNode> originalMethods = targetNode.methods;
        //targetNode.methods = targetNode.methods.stream().filter(this::isNotInjected).collect(Collectors.toList());

        //List<FieldNode> originalFields = targetNode.fields;
        //targetNode.fields = targetNode.fields.stream().filter(this::isNotInjected).collect(Collectors.toList());


        // Accept writer
        targetNode.accept(writer);

        // Restore originals
        //targetNode.methods = originalMethods;
        //targetNode.fields = originalFields;

        // Inject methods and fields
        //injectMethods.forEach(node -> node.accept(writer));
        //injectFields.forEach(node -> node.accept(writer));

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


    /*
    protected boolean isNotInjected(MethodNode node) {
        for (MethodNode mNode : injectMethods)
            if (methodNodeEquals(node, mNode))
                return false;

        return true;
    }

    protected boolean isNotInjected(FieldNode node) {
        for (FieldNode mNode : injectFields)
            if (fieldNodeEquals(node, mNode))
                return false;

        return true;
    }
    */

    // To be used instead of referencing object constructs
    public static Object field(String name) {
        throw new RuntimeException("Field not injected");
    }

    public static void superCall(String superMethodName){
        throw new RuntimeException("Super call not injected");
    }


    enum SpecialCall {
        FIELD, METHOD, SUPER
    }


    @Nullable
    protected static SpecialCall getSpecialCall(MethodInsnNode node) {
        if (!node.owner.equals("dev/w1zzrd/asm/Merger")) return null;

        switch (node.name) {
            case "field":
                return FIELD;

            case "method":
                return METHOD;

            case "superCall":
                return SUPER;

            default:
                return null;
        }
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

    @Nullable
    protected static AsmAnnotation getAnnotation(String annotationType, MethodNode cNode) {
        for (AnnotationNode aNode : cNode.visibleAnnotations)
            if (aNode.desc.equals(annotationType)) {
                HashMap<String, Object> map = new HashMap<>();

                // Collect annotation values
                if (aNode.values != null)
                    NODE_LOOP:
                    for (int i = 1; i < aNode.values.size(); i+=2) {
                        String key = (String) aNode.values.get(i - 1);
                        Object toPut = aNode.values.get(i);

                        if (toPut instanceof String[] && ((String[]) toPut).length == 2) {
                            String enumType = ((String[])toPut)[0];
                            String enumName = ((String[])toPut)[1];
                            if (enumType.startsWith("L") && enumType.endsWith(";"))
                                try{
                                    Class<?> type = Class.forName(enumType.substring(1, enumType.length()-1).replace('/', '.'));
                                    Method m = Enum.class.getDeclaredMethod("name");
                                    Object[] values = (Object[]) type.getDeclaredMethod("values").invoke(null);

                                    for (Object value : values)
                                        if (m.invoke(value).equals(enumName)) {
                                            map.put(key, value);
                                            continue NODE_LOOP;
                                        }

                                } catch (Throwable e) {
                                    /* Just ignore */
                                }
                        }

                        // Default insertion policy
                        map.put(key, toPut);
                    }

                return new AsmAnnotation(annotationType, map);
            }

        return null;
    }

    protected static boolean methodNodeEquals(MethodNode a, MethodNode b) {
        return a.name.equals(b.name) && Objects.equals(a.desc, b.desc);
    }

    protected static boolean fieldNodeEquals(FieldNode a, FieldNode b) {
        return a.name.equals(b.name) && Objects.equals(a.signature, b.signature);
    }

    protected static boolean shouldInject(MethodNode node) {
        if (node.visibleAnnotations == null) return false;
        for (AnnotationNode aNode : node.visibleAnnotations)
            if (aNode.desc.equals("Ldev/w1zzrd/asm/Inject;"))
                return true;

        return false;
    }

    protected static boolean shouldInject(FieldNode node) {
        if (node.visibleAnnotations == null) return false;
        for (AnnotationNode aNode : node.visibleAnnotations)
            if (aNode.desc.equals("Ldev/w1zzrd/asm/Inject;"))
                return true;

        return false;
    }

    protected static boolean removeReturn(List<AbstractInsnNode> instr, LabelNode jumpReplace) {
        ListIterator<AbstractInsnNode> iter = instr.listIterator();
        JumpInsnNode finalJump = null;
        int keepLabel = 0;
        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (node instanceof InsnNode && node.getOpcode() >= Opcodes.IRETURN && node.getOpcode() <= Opcodes.RETURN) {
                iter.remove();

                // Make sure to properly pop values from the stack
                // TODO: Optimize LDC's and field load calls here
                if (node.getOpcode() == Opcodes.LRETURN || node.getOpcode() == Opcodes.DRETURN)
                    iter.add(new InsnNode(Opcodes.POP2));
                else if (node.getOpcode() != Opcodes.RETURN)
                    iter.add(new InsnNode(Opcodes.POP));

                iter.add(finalJump = new JumpInsnNode(Opcodes.GOTO, jumpReplace));
                ++keepLabel;
            }
        }

        if (finalJump != null) // This *should* always be true
            instr.remove(finalJump);

        return keepLabel <= 1;
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
