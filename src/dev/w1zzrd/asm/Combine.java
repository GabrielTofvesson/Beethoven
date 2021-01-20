package dev.w1zzrd.asm;

import dev.w1zzrd.asm.analysis.AsmAnnotation;
import dev.w1zzrd.asm.exception.MethodNodeResolutionException;
import dev.w1zzrd.asm.exception.SignatureInstanceMismatchException;
import dev.w1zzrd.asm.signature.MethodSignature;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

public class Combine {
    private final ClassNode target;


    public Combine(ClassNode target) {
        this.target = target;
    }

    public void inject(MethodNode node, GraftSource source) {
        final AsmAnnotation<Inject> annotation = source.getInjectAnnotation(node);

        final boolean acceptReturn = annotation.getEntry("acceptOriginalReturn");

        switch ((InPlaceInjection)annotation.getEnumEntry("value")) {
            case INSERT: // Explicitly insert a *new* method
                insert(node, source);
                break;
            case REPLACE: // Explicitly replace an *existing* method
                replace(node, source, true);
                break;
            case INJECT: // Insert method by either replacing an existing method or inserting a new method
                insertOrReplace(node, source);
                break;
            case AFTER: // Inject a method's instructions after the original instructions in a given method
                append(node, source, acceptReturn);
                break;
            case BEFORE: // Inject a method's instructions before the original instructions in a given method
                prepend(node, source);
                break;
        }
    }

    /**
     * Extend implementation of a method past its regular return. This grafts the given method node to the end of the
     * targeted method node, such that, instead of returning, the code in the given method node is executed with the
     * return value from the original node as the "argument" to the grafted node.
     * @param extension Node to extend method with
     * @param source The {@link GraftSource} from which the method node will be adapted
     * @param acceptReturn Whether or not the grafted method should "receive" the original method's return value as an "argument"
     */
    public void append(MethodNode extension, GraftSource source, boolean acceptReturn) {
        final MethodNode target = checkMethodExists(source.getMethodTargetName(extension), source.getMethodTargetSignature(extension));
        adaptMethod(extension, source);

    }

    public void prepend(MethodNode extension, GraftSource source) {
        final MethodNode target = checkMethodExists(source.getMethodTargetName(extension), source.getMethodTargetSignature(extension));
        adaptMethod(extension, source);

    }

    public void replace(MethodNode inject, GraftSource source, boolean preserveOriginalAccess) {
        final MethodNode remove = checkMethodExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));
        ensureMatchingSignatures(remove, inject, Opcodes.ACC_STATIC);
        if (preserveOriginalAccess)
            copySignatures(remove, inject, Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);

        insertOrReplace(inject, source);
    }

    public void insert(MethodNode inject, GraftSource source) {
        checkMethodNotExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));
        insertOrReplace(inject, source);
    }

    protected void insertOrReplace(MethodNode inject, GraftSource source) {
        MethodNode replace = findMethodNode(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));

        if (replace != null)
            this.target.methods.remove(replace);

        adaptMethod(inject, source);

        this.target.methods.add(inject);
    }



    /**
     * Compile target class data to a byte array
     * @return Class data
     */
    public byte[] toByteArray() {
        return toByteArray(COMPUTE_MAXS);
    }

    /**
     * Compile target class data to a byte array
     * @param writerFlags Flags to pass to the {@link ClassWriter} used to compile the target class
     * @return Class data
     */
    public byte[] toByteArray(int writerFlags) {
        ClassWriter writer = new ClassWriter(writerFlags);
        //target.methods.forEach(method -> method.localVariables.forEach(var -> var.name = var.name.replace(" ", "")));
        target.accept(writer);

        return writer.toByteArray();
    }

    /**
     * Compile target class data to byte array and load with system class loader
     * @return Class loaded by the loader
     */
    public Class<?> compile() {
        return compile(ClassLoader.getSystemClassLoader());
    }

    /**
     * Compile target class data to byte array and load with the given class loader
     * @param loader Loader to use when loading the class
     * @return Class loaded by the loader
     */
    public Class<?> compile(ClassLoader loader) {
        Method m = null;
        try {
            m = ClassLoader.class.getDeclaredMethod("defineClass", byte[].class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }

        assert m != null;
        m.setAccessible(true);
        //ReflectCompat.setAccessible(m, true);

        byte[] data = toByteArray();

        try {
            return (Class<?>) m.invoke(loader, data, 0, data.length);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Prepares a {@link MethodNode} for grafting on to a given method and into the targeted  {@link ClassNode}
     * @param node Node to adapt
     * @param source The {@link GraftSource} from which the node will be adapted
     */
    protected void adaptMethod(MethodNode node, GraftSource source) {
        final AbstractInsnNode last = node.instructions.getLast();
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != last; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) adaptMethodInsn((MethodInsnNode) insn, source);
            else if (insn instanceof LdcInsnNode) adaptLdcInsn((LdcInsnNode) insn, source.getTypeName());
            else if (insn instanceof FrameNode) adaptFrameNode((FrameNode) insn, node, source);
        }

        node.name = source.getMethodTargetName(node);
    }


    /**
     * Adapts a grafted method instruction node to fit its surrogate
     * @param node Grafted method instruction node
     * @param source The {@link GraftSource} from which the instruction node will be adapted
     */
    protected void adaptMethodInsn(MethodInsnNode node, GraftSource source) {
        if (node.owner.equals(source.getTypeName())) {
            final MethodNode injected = source.getInjectedMethod(node.name, node.desc);
            if (injected != null) {
                node.owner = this.target.name;
                node.name = source.getMethodTargetName(injected);
            }
        }
    }

    /**
     * Adapts a grafted constant instruction node to fit its surrogate
     * @param node Grafted LDC instruction node
     * @param originalOwner Fully-qualified name of the original owner class
     */
    protected void adaptLdcInsn(LdcInsnNode node, String originalOwner) {
        if (node.cst instanceof Type && ((Type) node.cst).getInternalName().equals(originalOwner))
            node.cst = Type.getType(String.format("L%s;", originalOwner));
    }

    protected void adaptFrameNode(FrameNode node, MethodNode method, GraftSource source) {

    }

    /**
     * Ensure that a method node matching the given description does not exist in the targeted class
     * @param name Name of the method node
     * @param descriptor Descriptor of the method node
     * @throws  MethodNodeResolutionException If a method matching the given description could be found
     */
    protected final void checkMethodNotExists(String name, MethodSignature descriptor) {
        final MethodNode target = findMethodNode(name, descriptor);

        if (target != null)
            throw new MethodNodeResolutionException(String.format(
                    "Cannot insert method node \"%s%s\" into class: node with name and signature already exists",
                    name,
                    descriptor
            ));
    }

    /**
     * Ensure that a method node matching the given description exists in the targeted class
     * @param name Name of the method node
     * @param descriptor Descriptor of the method node
     * @return The located method node
     * @throws MethodNodeResolutionException If no method node matching the given description could be found
     */
    protected final @NotNull MethodNode checkMethodExists(String name, MethodSignature descriptor) {
        final MethodNode target = findMethodNode(name, descriptor);

        if (target == null)
            throw new MethodNodeResolutionException(String.format(
                    "Cannot replace method node \"%s\" in class: node with name and signature does not exist",
                    name
            ));

        return target;
    }

    /**
     * Find a method node in the targeted class by name and descriptor
     * @param name Name of the method node to find
     * @param desc Descriptor of the method node to find
     * @return A matching {@link MethodNode} if one exists, else null
     */
    protected @Nullable MethodNode findMethodNode(String name, MethodSignature desc) {
        return target.methods
                .stream()
                .filter(it -> it.name.equals(name) && new MethodSignature(it.desc).equals(desc))
                .findFirst()
                .orElse(null);
    }

    /**
     * Ensure that the injection method has matching access flags as the targeted method
     * @param target Targeted method
     * @param inject Injected method
     * @param flags Flags to check equality of (see {@link Opcodes})
     */
    protected static void ensureMatchingSignatures(MethodNode target, MethodNode inject, int flags) {
        if ((target.access & flags) != (inject.access & flags))
            throw new SignatureInstanceMismatchException(String.format(
                    "Access flag mismatch for target method %s (with flags %d) and inject method %s (with flags %d)",
                    target.name,
                    target.access & flags,
                    inject.name,
                    inject.access & flags
            ));
    }

    /**
     * Copy access flags from the targeted method to the injected method
     * @param target Targeted method
     * @param inject Injected method
     * @param flags Flags to copy (see {@link Opcodes})
     */
    protected static void copySignatures(MethodNode target, MethodNode inject, int flags) {
        inject.access ^= (inject.access & flags) ^ (target.access & flags);
    }



    public static java.util.List<? extends AbstractInsnNode> dumpInsns(MethodNode node) {
        return Arrays.asList(node.instructions.toArray());
    }
}
