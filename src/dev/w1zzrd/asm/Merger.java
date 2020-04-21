package dev.w1zzrd.asm;

import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static dev.w1zzrd.asm.Merger.SpecialCall.FIELD;
import static dev.w1zzrd.asm.Merger.SpecialCall.SUPER;
import static jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

public class Merger {

    private static final Pattern re_methodSignature = Pattern.compile("((?:[a-zA-Z_$][a-zA-Z\\d_$]+)|(?:<init>))\\(((?:(?:\\[*L(?:[a-zA-Z_$][a-zA-Z\\d_$]*/)*[a-zA-Z_$][a-zA-Z\\d_$]*;)|Z|B|C|S|I|J|F|D)*)\\)((?:\\[*L(?:[a-zA-Z_$][a-zA-Z\\d_$]*/)*[a-zA-Z_$][a-zA-Z\\d_$]*;)|Z|B|C|S|I|J|F|D|V)");
    private static final Pattern re_types = Pattern.compile("((?:\\[*L(?:[a-zA-Z_$][a-zA-Z\\d_$]*/)*[a-zA-Z_$][a-zA-Z\\d_$]*;)|Z|B|C|S|I|J|F|D)");
    private static final Pattern re_retTypes = Pattern.compile("((?:\\[*L(?:[a-zA-Z_$][a-zA-Z\\d_$]*/)*[a-zA-Z_$][a-zA-Z\\d_$]*;)|Z|B|C|S|I|J|F|D|V)");

    protected final ClassNode targetNode;


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

            AsmAnnotation<InjectClass> injectAnnotation = getAnnotation(InjectClass.class, inject);

            // If there is not injectMethods annotation or there is an
            // explicit request to not injectMethods interfaces, just return
            if (injectAnnotation == null ||
                    (injectAnnotation.hasEntry("injectInterfaces") &&
                            !(Boolean)injectAnnotation.getEntry("injectInterfaces")))
                return;


            if (targetNode.interfaces == null)
                targetNode.interfaces = new ArrayList<>();


            inject.interfaces.stream().filter(it -> !targetNode.interfaces.contains(it)).forEach(targetNode.interfaces::add);
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


        MethodSig signature = getSignature(inject);
        AsmAnnotation<Inject> annotation = getAnnotation(Inject.class, inject);
        if (annotation != null && annotation.hasEntry("value")) {

            InPlaceInjection injection = Objects.requireNonNull(annotation.getEntry("value"));
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
                        if (removeReturn(instr, next, true, null) && created)
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

                        boolean isStaticMethod = isStatic(inject);
                        boolean keepReturn = annotation.hasEntry("acceptOriginalReturn") && (Boolean)annotation.getEntry("acceptOriginalReturn");
                        boolean hasReturn = !signature.ret.equals("V");

                        // Received return value
                        LocalVariableNode retNode = keepReturn && hasReturn ? inject.localVariables.get((isStaticMethod ? 0 : 1) + signature.args.length) : null;
                        Optional<LocalVariableNode> retNode_decl = adapt.get().localVariables.stream().filter(it -> it.name.startsWith(" ")).findFirst();

                        // Set the proper index and update maxLocals accordingly
                        // This is done here so that injected calls to modify this variable use the correct index
                        if (keepReturn && hasReturn) {
                            if (retNode_decl.isPresent()) {
                                // Extend scope of retNode variable
                                retNode_decl.get().end = retNode.end;

                                // Remove unnecessary retNode
                                inject.localVariables.remove(retNode);
                            }
                            else {
                                // Insert retNode at the lowest index that isn't "this"
                                int lowestIndex = isStaticMethod ? 0 : 1;
                                int original = retNode.index;
                                retNode.index = lowestIndex;

                                toAdapt
                                        .stream()
                                        .filter(it -> it instanceof VarInsnNode && ((VarInsnNode) it).var >= retNode.index)
                                        .forEach(it -> ++((VarInsnNode) it).var);
                                instr
                                        .stream()
                                        .filter(it -> it instanceof VarInsnNode && ((VarInsnNode) it).var >= retNode.index)
                                        .forEach(it -> {
                                            if (((VarInsnNode) it).var == original)
                                                ((VarInsnNode) it).var = retNode.index;
                                            else ++((VarInsnNode) it).var;
                                        });

                                inject.maxLocals = Math.max(inject.maxLocals, adapt.get().maxLocals);

                                // Mark variable as retVal holder in the most lazy way I could think of
                                retNode.name = ' ' + retNode.name;
                            }
                        }

                        // If no goto instructions were added, just remove the added label
                        boolean noGoto = removeReturn(toAdapt, next, !keepReturn && hasReturn, retNode);
                        if(noGoto) {
                            if(created)
                                instr.remove(next);
                        }
                        else // A goto call was added. Make sure we inform the JVM of stack and locals with a frame
                            instr.add(1, new FrameNode(Opcodes.F_SAME, -1, null, -1, null));

                        instr.addAll(0, toAdapt);

                        if (keepReturn && hasReturn) {
                            // A little bit overkill, but I'm lazy
                            LabelNode first;
                            if (instr.get(0) instanceof LabelNode)
                                first = (LabelNode) instr.get(0);
                            else {
                                first = new LabelNode();
                                instr.add(0, first);
                            }

                            // Make the scope of received retVal span the entire method
                            retNode.start = first;
                        }

                        break;
                    }
                }

                // Add the original locals to the injection
                inject.localVariables.addAll(adapt.get().localVariables.stream().filter(it -> !it.name.equals("this")).collect(Collectors.toList()));
                Optional<LocalVariableNode> injectThis = inject.localVariables.stream().filter(it -> it.name.equals("this")).findFirst();
                Optional<LocalVariableNode> origThis = adapt.get().localVariables.stream().filter(it -> it.name.equals("this")).findFirst();

                if (injectThis.isPresent() != origThis.isPresent())
                    throw new RuntimeException("Method modifier mismatch! Cannot weave a static method and non-static method!");

                // Update the scope of "this". It always has index 0 and spans the whole method
                if (injectThis.isPresent()) {
                    origThis.get().end = injectThis.get().end;
                    inject.localVariables.add(origThis.get());
                    inject.localVariables.remove(injectThis.get());
                }

                // Ensure all locals have a unique name
                inject.localVariables.forEach(it -> makeLocalUnique(it, inject.localVariables));
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

        // Apply signature overrides
        inject.name = signature.name;
        inject.desc = '(' + signature.args_literal + ')' + signature.ret;
    }

    public boolean shouldInject(ClassNode inject) {
        AsmAnnotation<InjectClass> injectAnnotation = getAnnotation(InjectClass.class, inject);
        return injectAnnotation != null &&
                ((Type)injectAnnotation.getEntry("value")).getClassName().equals(getTargetName());
    }

    public byte[] toByteArray() {
        return toByteArray(COMPUTE_MAXS);
    }

    public byte[] toByteArray(int writerFlags) {
        ClassWriter writer = new ClassWriter(writerFlags);
        targetNode.methods.forEach(method -> method.localVariables.forEach(var -> var.name = var.name.replace(" ", "")));
        targetNode.accept(writer);

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

    // To be used instead of referencing object constructs
    public static Object field(String name) {
        throw new RuntimeException("Field not injected");
    }

    public static void superCall(String superMethodName){
        throw new RuntimeException("Super call not injected");
    }


    enum SpecialCall {
        FIELD, SUPER
    }


    protected static SpecialCall getSpecialCall(MethodInsnNode node) {
        if (!node.owner.equals("dev/w1zzrd/asm/Merger")) return null;

        switch (node.name) {
            case "field":
                return FIELD;

            case "superCall":
                return SUPER;

            default:
                return null;
        }
    }


    protected static <T extends Annotation> AsmAnnotation<T> getAnnotation(Class<T> annotationType, ClassNode cNode) {
        if(cNode.visibleAnnotations == null)
            return null;

        String targetAnnot = 'L' + annotationType.getTypeName().replace('.', '/') + ';';

        for (AnnotationNode aNode : cNode.visibleAnnotations)
            if (aNode.desc.equals(targetAnnot)) {
                HashMap<String, Object> map = new HashMap<>();

                // Collect annotation values
                if (aNode.values != null)
                    for (int i = 1; i < aNode.values.size(); i+=2)
                        map.put((String)aNode.values.get(i - 1), aNode.values.get(i));

                return new AsmAnnotation<>(annotationType, map);
            }

        return null;
    }

    protected static <T extends Annotation> AsmAnnotation<T> getAnnotation(Class<T> annotationType, MethodNode cNode) {
        if(cNode.visibleAnnotations == null)
            return null;

        String targetAnnot = 'L' + annotationType.getTypeName().replace('.', '/') + ';';

        for (AnnotationNode aNode : cNode.visibleAnnotations)
            if (aNode.desc.equals(targetAnnot)) {
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

                return new AsmAnnotation<>(annotationType, map);
            }

        return null;
    }

    protected static boolean methodNodeEquals(MethodNode a, MethodNode b) {
        return getSignature(a).equals(getSignature(b)) && (isStatic(a) == isStatic(b));
    }

    protected static boolean isStatic(MethodNode node) {
        return (node.access & Opcodes.ACC_STATIC) != 0;
    }

    protected static MethodSig getSignature(MethodNode node) {
        AsmAnnotation<Inject> annotation = getAnnotation(Inject.class, node);
        MethodSig actualSignature = Objects.requireNonNull(parseMethodSignature(node.name + node.desc));

        // Attempt to parse a declared signature
        if (annotation != null && annotation.hasEntry("target")) {
            MethodSig overrideSignature = parseMethodSignature(annotation.getEntry("target"));

            if (overrideSignature != null) {
                // Ensure the signatures are compatible
                if (!Arrays.equals(overrideSignature.args, actualSignature.args)) {
                    if (overrideSignature.args.length + 1 != actualSignature.args.length)
                        throw new RuntimeException(String.format("Unreasonable signature declaration for method %s (actually %s)", overrideSignature.toString(), actualSignature.toString()));

                    for (int i = 0; i < overrideSignature.args.length; ++i)
                        if (!overrideSignature.args[i].equals(actualSignature.args[i]))
                            throw new RuntimeException(String.format("Signature mismatch for method %s (actually %s)", overrideSignature.toString(), actualSignature.toString()));

                    if (!actualSignature.args[overrideSignature.args.length].equals(overrideSignature.ret))
                        throw new RuntimeException(String.format("Unreasonable additional argument declaration for method %s (actually %s)", overrideSignature.toString(), actualSignature.toString()));
                }

                if (!overrideSignature.ret.equals(actualSignature.ret))
                    throw new RuntimeException(String.format("Unreasonable return declaration for method %s (actually %s)", overrideSignature.toString(), actualSignature.toString()));

                // We have target signature override
                // Use this instead of the actual method signature
                return overrideSignature;
            }
        }

        // Parse implicit signature
        return actualSignature;
    }

    protected static void makeLocalUnique(LocalVariableNode node, List<LocalVariableNode> other) {
        while (other.stream().anyMatch(it -> it != node && it.name.equals(node.name)))
            node.name = '$'+node.name;
    }

    protected static MethodSig parseMethodSignature(String sig) {
        Matcher signatureMatcher = re_methodSignature.matcher(sig);

        if (sig.length() > 0 && signatureMatcher.matches()) {
            String name = signatureMatcher.group(1);
            String ret = signatureMatcher.group(3);

            Matcher argMatcher = re_types.matcher(signatureMatcher.group(2));
            ArrayList<String> args = new ArrayList<>();
            while (argMatcher.find())
                args.add(argMatcher.group(1));

            return new MethodSig(name, ret, args.toArray(new String[args.size()]));
        }

        return null;
    }

    /**
     * Data class for storing method signature and name
     */
    protected static final class MethodSig {
        public final String name;
        public final String ret;
        public final String[] args;
        public final String args_literal;

        public MethodSig(String name, String ret, String[] args) {
            this.name = name;
            this.ret = ret;
            this.args = args;

            StringBuilder builder = new StringBuilder();
            for (String s : args)
                builder.append(s);

            args_literal = builder.toString();
        }

        @Override
        public String toString() {
            return name+'('+args_literal+')'+ret;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof MethodSig && toString().equals(obj.toString());
        }
    }



    protected static Object resolveFrameType(String typeString) {
        Type sigType = Type.getType(typeString);
        switch (sigType.getSort()) {
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
                return Opcodes.INTEGER;
            case 6:
                return Opcodes.FLOAT;
            case 7:
                return Opcodes.LONG;
            case 8:
                return Opcodes.DOUBLE;
            case 9:
                return sigType.getDescriptor();
            default:
                return sigType.getInternalName();
        }
    }

    protected static int resolveStoreInstr(String typeString) {
        switch (typeString) {
            case "Z":
            case "I":
            case "B":
            case "C":
            case "S":
                return Opcodes.ISTORE;
            case "J":
                return Opcodes.LSTORE;
            case "F":
                return Opcodes.FSTORE;
            case "D":
                return Opcodes.DSTORE;

            // Void has no store type
            case "V":
                return -1;

            default:
                return Opcodes.ASTORE;
        }
    }

    protected static boolean fieldNodeEquals(FieldNode a, FieldNode b) {
        return a.name.equals(b.name) && Objects.equals(a.signature, b.signature);
    }

    protected static boolean shouldInject(MethodNode node) {
        if (node.visibleAnnotations == null) return false;

        String targetDesc = 'L' + Inject.class.getTypeName().replace('.', '/') + ';';

        for (AnnotationNode aNode : node.visibleAnnotations)
            if (aNode.desc.equals(targetDesc))
                return true;

        return false;
    }

    protected static boolean shouldInject(FieldNode node) {
        if (node.visibleAnnotations == null) return false;

        String targetDesc = 'L' + Inject.class.getTypeName().replace('.', '/') + ';';

        for (AnnotationNode aNode : node.visibleAnnotations)
            if (aNode.desc.equals(targetDesc))
                return true;

        return false;
    }

    protected static boolean removeReturn(List<AbstractInsnNode> instr, LabelNode jumpReplace, boolean popReturn, LocalVariableNode storeNode) {
        ListIterator<AbstractInsnNode> iter = instr.listIterator();
        JumpInsnNode finalJump = null;
        int keepLabel = 0;
        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (node instanceof InsnNode && node.getOpcode() >= Opcodes.IRETURN && node.getOpcode() <= Opcodes.RETURN) {
                iter.remove();

                // If we're not keeping the return value and the return
                // value is gotten from a method call, just pop the result of the call
                if(popReturn && !removeRedundantLoad(iter)) {
                    if (node.getOpcode() == Opcodes.LRETURN || node.getOpcode() == Opcodes.DRETURN)
                        iter.add(new InsnNode(Opcodes.POP2));
                    else if (node.getOpcode() != Opcodes.RETURN)
                        iter.add(new InsnNode(Opcodes.POP));
                } else {
                    iter.add(new VarInsnNode(resolveStoreInstr(storeNode.desc), storeNode.index));
                }

                iter.add(finalJump = new JumpInsnNode(Opcodes.GOTO, jumpReplace));
                ++keepLabel;
            }
        }

        if (finalJump != null) // This *should* always be true
            instr.remove(finalJump);

        return keepLabel <= 1;
    }

    protected static boolean removeRedundantLoad(ListIterator<AbstractInsnNode> iter) {
        boolean hasEffects = false;
        int iterCount = 0;
        while (iter.hasPrevious()) {
            AbstractInsnNode node = iter.previous();
            ++iterCount;

            if (node instanceof MethodInsnNode) {
                hasEffects = true;
                break;
            }

            if ((node instanceof FieldInsnNode && node.getOpcode() == Opcodes.GETSTATIC) ||
                    (node instanceof InsnNode && (node.getOpcode() == Opcodes.LDC ||
                            (node.getOpcode() >= Opcodes.ILOAD && node.getOpcode() <= Opcodes.ALOAD) ||
                            (node.getOpcode() >= Opcodes.IALOAD && node.getOpcode() <= Opcodes.SALOAD))))
                break;
        }

        for(int i = 0; i < iterCount; ++i) {
            iter.next();
            if (!hasEffects)
                iter.remove();
        }

        return hasEffects;
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
