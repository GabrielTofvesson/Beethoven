package dev.w1zzrd.asm;

import dev.w1zzrd.asm.analysis.AsmAnnotation;
import dev.w1zzrd.asm.exception.MethodNodeResolutionException;
import dev.w1zzrd.asm.exception.SignatureInstanceMismatchException;
import dev.w1zzrd.asm.signature.MethodSignature;
import dev.w1zzrd.asm.signature.TypeSignature;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

public class Combine {
    private static final String VAR_NAME_CHARS = "$_qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM";
    private static final String VAR_NAME_CHARS1 = "$_qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM1234567890";

    private final ArrayList<DynamicSourceUnit> graftSources = new ArrayList<>();

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
        if (initiateGrafting(extension, source))
            return;

        final MethodNode target = checkMethodExists(source.getMethodTargetName(extension), source.getMethodTargetSignature(extension));
        adaptMethod(extension, source);

        MethodSignature msig = new MethodSignature(target.desc);
        MethodSignature xsig = new MethodSignature(extension.desc);


        List<AbstractInsnNode> targetInsns;

        // If graft method cares about the return value of the original method
        if (acceptReturn) {
            LocalVariableNode retVar = null;

            // If return of original is not void, we need to capture and store it to pass to the extension code
            if (!msig.getRet().isVoidType()) {
                // Generate a random return var name
                String name;
                GEN_NAME:
                do {
                    name = getRandomString(1, 16);
                    for (LocalVariableNode vNode : target.localVariables)
                        if (name.equals(vNode.name))
                            continue GEN_NAME;

                    break;
                } while (true);

                // Create return variable
                retVar = insertRetvarNode(target, name, msig.getRet());
            }

            // Convert instructions into a more modifiable format
            targetInsns = decomposeToList(target.instructions);

            // Replace return instructions with GOTOs to the last instruction in the list
            // Return values are stored in retVar
            storeAndGotoFromReturn(targetInsns, retVar == null ? -1 : retVar.index);

            // We need to extend the scope of the retVar into the grafted code
            if (retVar != null)
                //noinspection OptionalGetWithoutIsPresent
                retVar.end = extension.localVariables
                        .stream()
                        .filter(it -> it.index == xsig.getArgCount() - 1)
                        .findFirst()
                        .get() // This should never fail
                        .end;
        } else {
            targetInsns = decomposeToList(target.instructions);

            // If we don't care about the return value from the original, we can replace returns with pops
            popAndGotoFromReturn(targetInsns);
        }

        List<LocalVariableNode> extVars = getVarsOver(extension.localVariables, xsig.getArgCount());

        // Add extension vars to target
        target.localVariables.addAll(extVars);

        // Add extension instructions to instruction list
        targetInsns.addAll(decomposeToList(extension.instructions));

        // Convert instructions back to a InsnList
        target.instructions = coalesceInstructions(targetInsns);
    }

    public void prepend(MethodNode extension, GraftSource source) {
        if (initiateGrafting(extension, source))
            return;

        final MethodNode target = checkMethodExists(source.getMethodTargetName(extension), source.getMethodTargetSignature(extension));
        adaptMethod(extension, source);

    }

    public void replace(MethodNode inject, GraftSource source, boolean preserveOriginalAccess) {
        if (initiateGrafting(inject, source))
            return;

        final MethodNode remove = checkMethodExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));
        ensureMatchingSignatures(remove, inject, Opcodes.ACC_STATIC);
        if (preserveOriginalAccess)
            copySignatures(remove, inject, Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);

        insertOrReplace(inject, source);
    }

    public void insert(MethodNode inject, GraftSource source) {
        if (initiateGrafting(inject, source))
            return;

        checkMethodNotExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));
        insertOrReplace(inject, source);
    }

    protected void insertOrReplace(MethodNode inject, GraftSource source) {
        if (initiateGrafting(inject, source))
            return;

        MethodNode replace = findMethodNode(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));

        if (replace != null)
            this.target.methods.remove(replace);

        adaptMethod(inject, source);

        this.target.methods.add(inject);
    }


    private boolean initiateGrafting(MethodNode node, GraftSource source) {
        DynamicSourceUnit unit = new DynamicSourceUnit(source, node);
        boolean alreadyGrafting = graftSources.contains(unit);

        if (!alreadyGrafting)
            graftSources.add(unit);

        return alreadyGrafting;
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
        // Adapt instructions
        ADAPT:
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) adaptMethodInsn((MethodInsnNode) insn, source);
            else if (insn instanceof LdcInsnNode) adaptLdcInsn((LdcInsnNode) insn, source.getTypeName());
            else if (insn instanceof FrameNode) adaptFrameNode((FrameNode) insn, node, source);
            else if (insn instanceof InvokeDynamicInsnNode && ((Handle)((InvokeDynamicInsnNode) insn).bsmArgs[1]).getOwner().equals(source.getTypeName())) {
                // We have an INVOKEDYNAMIC to a method in the graft source class. The target has to be injected into the target
                Handle handle = (Handle)((InvokeDynamicInsnNode) insn).bsmArgs[1];

                for (MethodNode mNode : target.methods)
                    if (mNode.name.equals(handle.getName()) && mNode.desc.equals(handle.getDesc()))
                        continue ADAPT; // The target has already been injected

                MethodNode inject = source.getMethodNode(handle.getName(), handle.getDesc());
                if (inject == null)
                    throw new MethodNodeResolutionException(String.format(
                            "Could not locate lambda target %s%s in graft source %s",
                            handle.getName(),
                            handle.getDesc(),
                            source.getTypeName()
                    ));

                // Attempt to inject lambda target site into target class
                insert(inject, source);

                // The INVOKEDYNAMIC now points to a call site in the target class
                ((InvokeDynamicInsnNode) insn).bsmArgs[1] = new Handle(
                        handle.getTag(),
                        target.name,
                        handle.getName(),
                        handle.getDesc()
                );
            }
        }

        node.name = source.getMethodTargetName(node);
    }

    private static LabelNode findOrMakeEndLabel(List<AbstractInsnNode> nodes) {
        AbstractInsnNode last = nodes.get(nodes.size() - 1);

        if (last instanceof LabelNode)
            return (LabelNode) last;

        LabelNode label = new LabelNode();

        nodes.add(label);
        return label;
    }

    private static void storeAndGotoFromReturn(List<AbstractInsnNode> nodes, int storeIndex) {
        LabelNode endLabel = findOrMakeEndLabel(nodes);

        INSTRUCTION_LOOP:
        for (int i = 0; i < nodes.size(); ++i) {
            switch (nodes.get(i).getOpcode()) {
                case Opcodes.IRETURN:
                    nodes.add(i, new IntInsnNode(Opcodes.ISTORE, storeIndex));
                    break;

                case Opcodes.FRETURN:
                    nodes.add(i, new IntInsnNode(Opcodes.FSTORE, storeIndex));
                    break;

                case Opcodes.ARETURN:
                    nodes.add(i, new IntInsnNode(Opcodes.ASTORE, storeIndex));
                    break;

                case Opcodes.LRETURN:
                    nodes.add(i, new IntInsnNode(Opcodes.LSTORE, storeIndex));
                    break;

                case Opcodes.DRETURN:
                    nodes.add(i, new IntInsnNode(Opcodes.DSTORE, storeIndex));
                    break;

                case Opcodes.RETURN:
                    --i;
                    break;

                default:
                    continue INSTRUCTION_LOOP;
            }

            nodes.set(i, new JumpInsnNode(Opcodes.GOTO, endLabel));
        }
    }

    private static void popAndGotoFromReturn(List<AbstractInsnNode> nodes) {
        LabelNode endLabel = findOrMakeEndLabel(nodes);

        INSTRUCTION_LOOP:
        for (int i = 0; i < nodes.size(); ++i) {
            switch (nodes.get(i).getOpcode()) {
                case Opcodes.IRETURN:
                case Opcodes.FRETURN:
                case Opcodes.ARETURN:
                    nodes.add(i, new InsnNode(Opcodes.POP));
                    break;

                case Opcodes.LRETURN:
                case Opcodes.DRETURN:
                    nodes.add(i, new InsnNode(Opcodes.POP2));
                    break;

                case Opcodes.RETURN:
                    --i;
                    break;

                default:
                    continue INSTRUCTION_LOOP;
            }

            nodes.set(i, new JumpInsnNode(Opcodes.GOTO, endLabel));
        }
    }

    private static List<AbstractInsnNode> decomposeToList(InsnList insns) {
        try {
            // This should save us some overhead
            Field elementData = ArrayList.class.getDeclaredField("elementData");
            elementData.setAccessible(true);
            Field size = ArrayList.class.getDeclaredField("size");
            size.setAccessible(true);

            // Make arraylist and get array of instructions
            ArrayList<AbstractInsnNode> decomposed = new ArrayList<>();
            AbstractInsnNode[] nodes = insns.toArray();

            // Copy instructions to arraylist
            elementData.set(decomposed, nodes);
            size.set(decomposed, nodes.length);

            return decomposed;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e); // Probably Java 9+
        }
    }


    private static LocalVariableNode insertRetvarNode(MethodNode node, String name, TypeSignature type) {
        // Finds first label or creates it
        LabelNode firstLabel = findLabelBeforeReturn(node.instructions.getFirst(), AbstractInsnNode::getNext);

        // No label found before a return: create one
        if (firstLabel == null)
            node.instructions.insert(firstLabel = new LabelNode());

        // Finds last label or creates it
        LabelNode lastLabel = findLabelBeforeReturn(node.instructions.getLast(), AbstractInsnNode::getPrevious);

        // No label found after a return: create one
        if (lastLabel == null)
            node.instructions.add(lastLabel = new LabelNode());


        // Put new variable immediately after the method arguments
        MethodSignature msig = new MethodSignature(node.desc);
        LocalVariableNode varNode = new LocalVariableNode(
                name,
                type.getSig(),
                null,
                firstLabel,
                lastLabel,
                msig.getArgCount()
        );

        // Increment existing variable indices by 1
        for (LocalVariableNode vNode : node.localVariables)
            if (vNode.index >= varNode.index)
                ++vNode.index;

        // Update instructions referencing local variables
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode && ((VarInsnNode) insn).var >= varNode.index)
                ++((VarInsnNode) insn).var;
        }

        // Add variable to locals
        node.localVariables.add(varNode);

        return varNode;
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
        adaptFrameTypes(node.stack, source);
        adaptFrameTypes(node.local, source);
    }


    protected void adaptFrameTypes(List<Object> types, GraftSource source) {
        if (types == null)
            return;

        for (int i = 0; i < types.size(); ++i) {
            if (types.get(i) instanceof Type) {
                Type t = (Type) types.get(i);

                if (t.getSort() == Type.OBJECT && source.getTypeName().equals(t.getInternalName()))
                    types.set(i, Type.getType(source.getTypeName()));
                else if (t.getSort() == Type.METHOD) {
                    TypeSignature sourceSig = new TypeSignature("L"+source.getTypeName()+";");
                    TypeSignature targetSig = new TypeSignature("L"+target.name+";");
                    MethodSignature mDesc = new MethodSignature(t.getDescriptor());
                    for (int j = 0; j < mDesc.getArgCount(); ++j)
                        if (mDesc.getArg(j).getArrayAtomType().equals(sourceSig))
                            mDesc.setArg(j, new TypeSignature(
                                    targetSig.getSig(),
                                    mDesc.getArg(j).getArrayDepth(),
                                    false
                            ));

                    if (mDesc.getRet().getArrayAtomType().equals(sourceSig))
                        mDesc.setRet(new TypeSignature(
                                targetSig.getSig(),
                                mDesc.getRet().getArrayDepth(),
                                false
                        ));
                }
            }
        }
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


    private static String getRandomString(int minLen, int maxLen) {
        Random r = ThreadLocalRandom.current();

        // Select a random length
        char[] str = new char[r.nextInt(maxLen - minLen) + minLen];

        // Generate random string
        str[0] = VAR_NAME_CHARS.charAt(r.nextInt(VAR_NAME_CHARS.length()));
        for(int i = 1; i < str.length; ++i)
            str[i] = VAR_NAME_CHARS1.charAt(r.nextInt(VAR_NAME_CHARS1.length()));

        return new String(str);
    }

    protected static InsnList coalesceInstructions(List<AbstractInsnNode> nodes) {
        InsnList insns = new InsnList();

        for(AbstractInsnNode node : nodes)
            insns.add(node);

        return insns;
    }

    protected static List<LocalVariableNode> getVarsOver(List<LocalVariableNode> varNodes, int minIndex) {
        return varNodes.stream().filter(it -> it.index >= minIndex).collect(Collectors.toList());
    }


    protected static @Nullable LabelNode findLabelBeforeReturn(AbstractInsnNode start, INodeTraversal traverse) {
        for (AbstractInsnNode cur = start; cur != null; cur = traverse.traverse(cur))
            if (cur instanceof LabelNode) // Traversal hit label
                return (LabelNode) cur;
            else if (cur.getOpcode() >= Opcodes.IRETURN && cur.getOpcode() <= Opcodes.RETURN) // Traversal hit return
                return null;

        return null; // Nothing was found
    }


    protected interface INodeTraversal {
        AbstractInsnNode traverse(AbstractInsnNode cur);
    }

    private static class DynamicSourceUnit {
        public final GraftSource source;
        public final MethodNode node;

        private DynamicSourceUnit(GraftSource source, MethodNode node) {
            this.source = source;
            this.node = node;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DynamicSourceUnit that = (DynamicSourceUnit) o;
            return source.equals(that.source) && node.equals(that.node);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, node);
        }
    }
}
