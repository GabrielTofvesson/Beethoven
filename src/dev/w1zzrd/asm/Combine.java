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

        final MethodNode target = checkMethodExists(
                source.getMethodTargetName(extension),
                source.getMethodTargetSignature(extension)
        );
        adaptMethod(extension, source);

        // Get the method signatures so we know what we're working with local-variable-wise ;)
        final MethodSignature msig = new MethodSignature(target.desc);
        final MethodSignature xsig = new MethodSignature(extension.desc);

        // Get total argument count, including implicit "this" argument
        final int graftArgCount = xsig.getArgCount() + (isStatic(extension) ? 0 : 1);
        final int targetArgCount = msig.getArgCount() + (isStatic(target) ? 0 : 1);

        // If graft method cares about the return value of the original method, i.e. accepts it as an extra "argument"
        if (acceptReturn && !msig.getRet().isVoidType()) {
            //noinspection OptionalGetWithoutIsPresent
            LocalVariableNode retVar = extension.localVariables
                    .stream()
                    .filter(it -> it.index == graftArgCount - 1)
                    .findFirst()
                    .get();

            // Inject return variable
            adjustArgument(target, retVar, true, true);

            // Handle retvar specially
            extension.localVariables.remove(retVar);

            // Make space in the original frames for the return var
            // This isn't an optimal solution, but it works for now
            adjustFramesForRetVar(target.instructions, targetArgCount);

            // Replace return instructions with GOTOs to the last instruction in the list
            // Return values are stored in retVar
            storeAndGotoFromReturn(target, target.instructions, retVar.index, xsig);
        } else {
            // If we don't care about the return value from the original, we can replace returns with pops
            popAndGotoFromReturn(target, target.instructions, xsig);
        }

        List<LocalVariableNode> extVars = getVarsOver(extension.localVariables, xsig.getArgCount());

        // Add extension vars to target
        target.localVariables.addAll(extVars);

        // Add extension instructions to instruction list
        target.instructions.add(extension.instructions);

        // Make sure we extend the scope of the original method arguments
        for (int i = 0; i < targetArgCount; ++i)
            adjustArgument(target, getVarAt(target.localVariables, i), false, false);

        // Recompute maximum variable count
        target.maxLocals = Math.max(
                Math.max(
                        targetArgCount + 1,
                        graftArgCount + 1
                ),
                target.localVariables
                        .stream()
                        .map(it -> it.index)
                        .max(Comparator.comparingInt(a -> a)).orElse(0) + 1
        );

        // Recompute maximum stack size
        target.maxStack = Math.max(target.maxStack, extension.maxStack);

        finishGrafting(extension, source);
    }

    public void prepend(MethodNode extension, GraftSource source) {
        if (initiateGrafting(extension, source))
            return;

        final MethodNode target = checkMethodExists(
                source.getMethodTargetName(extension),
                source.getMethodTargetSignature(extension)
        );
        adaptMethod(extension, source);

        MethodSignature sig = new MethodSignature(extension.desc);

        target.localVariables.addAll(getVarsOver(extension.localVariables, sig.getArgCount()));
        extension.instructions.add(target.instructions);

        target.instructions = extension.instructions;

        // Extend argument scope to cover prepended code
        for (int i = 0; i < sig.getArgCount(); ++i)
            adjustArgument(target, getVarAt(target.localVariables, i), true, false);

        finishGrafting(extension, source);
    }

    public void replace(MethodNode inject, GraftSource source, boolean preserveOriginalAccess) {
        if (initiateGrafting(inject, source))
            return;

        final MethodNode remove = checkMethodExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));
        ensureMatchingSignatures(remove, inject, Opcodes.ACC_STATIC);
        if (preserveOriginalAccess)
            copySignatures(remove, inject, Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);

        insertOrReplace(inject, source);

        finishGrafting(inject, source);
    }

    public void insert(MethodNode inject, GraftSource source) {
        if (initiateGrafting(inject, source))
            return;

        checkMethodNotExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject));
        insertOrReplace(inject, source);

        finishGrafting(inject, source);
    }

    protected void insertOrReplace(MethodNode inject, GraftSource source) {
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

    private void finishGrafting(MethodNode node, GraftSource source) {
        if (!graftSources.remove(new DynamicSourceUnit(source, node)))
            throw new IllegalStateException("Attempt to finish grafting when grafting is not it progress!");
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

    public String getTargetName() {
        return target.name;
    }

    /**
     * Prepares a {@link MethodNode} for grafting on to a given method and into the targeted  {@link ClassNode}
     * @param node Node to adapt
     * @param source The {@link GraftSource} from which the node will be adapted
     */
    protected void adaptMethod(MethodNode node, GraftSource source) {
        // Adapt instructions
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) adaptMethodInsn((MethodInsnNode) insn, source, node);
            else if (insn instanceof LdcInsnNode) adaptLdcInsn((LdcInsnNode) insn, source.getTypeName());
            else if (insn instanceof FrameNode) adaptFrameNode((FrameNode) insn, source);
            else if (insn instanceof FieldInsnNode) adaptFieldInsn((FieldInsnNode) insn, source);
            else if (insn instanceof InvokeDynamicInsnNode) adaptInvokeDynamicInsn((InvokeDynamicInsnNode) insn, source);
        }

        // Adapt variable types
        final String graftTypeName = "L"+source.getTypeName()+";";
        for (LocalVariableNode varNode : node.localVariables)
            if (graftTypeName.equals(varNode.desc))
                varNode.desc = graftTypeName;

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

    protected static LabelNode findOrMakeEndLabel(InsnList nodes) {
        AbstractInsnNode last = nodes.getLast();

        while (last instanceof FrameNode) last = last.getPrevious();

        if (last instanceof LabelNode)
            return (LabelNode) last;

        LabelNode label = new LabelNode();

        nodes.add(label);
        return label;
    }

    protected static boolean hasEndJumpFrame(InsnList nodes) {
        return nodes.getLast() instanceof FrameNode && nodes.getLast().getPrevious() instanceof LabelNode;
    }

    protected LabelNode makeEndJumpFrame(InsnList nodes, MethodSignature sig, MethodNode source) {
        LabelNode endLabel = findOrMakeEndLabel(nodes);

        List<Object> local = makeFrameLocals(sig.getArgs());
        if (!isStatic(source))
            local.add(0, target.name);

        //nodes.add(frameInsert + 1, new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        nodes.insert(endLabel, new FrameNode(Opcodes.F_FULL, local.size(), local.toArray(), 0, new Object[0]));

        return endLabel;
    }

    private void storeAndGotoFromReturn(MethodNode source, InsnList nodes, int storeIndex, MethodSignature sig) {
        // If we already have a final frame, there's no need to add one
        LabelNode endLabel = hasEndJumpFrame(nodes) ? findOrMakeEndLabel(nodes) : makeEndJumpFrame(nodes, sig, source);

        INSTRUCTION_LOOP:
        for (AbstractInsnNode current = nodes.getFirst(); current != null; current = current.getNext()) {
            switch (current.getOpcode()) {
                case Opcodes.IRETURN:
                    nodes.set(current, current = new IntInsnNode(Opcodes.ISTORE, storeIndex));
                    break;

                case Opcodes.FRETURN:
                    nodes.set(current, current = new IntInsnNode(Opcodes.FSTORE, storeIndex));
                    break;

                case Opcodes.ARETURN:
                    nodes.set(current, current = new IntInsnNode(Opcodes.ASTORE, storeIndex));
                    break;

                case Opcodes.LRETURN:
                    nodes.set(current, current = new IntInsnNode(Opcodes.LSTORE, storeIndex));
                    break;

                case Opcodes.DRETURN:
                    nodes.set(current, current = new IntInsnNode(Opcodes.DSTORE, storeIndex));
                    break;

                case Opcodes.RETURN:
                    nodes.set(current, current = new JumpInsnNode(Opcodes.GOTO, endLabel));
                    // Fallthrough

                default:
                    continue INSTRUCTION_LOOP;
            }
            nodes.insert(current, current = new JumpInsnNode(Opcodes.GOTO, endLabel));
        }
    }

    private void popAndGotoFromReturn(MethodNode source, InsnList nodes, MethodSignature sig) {
        // If we already have a final frame, there's no need to add one
        LabelNode endLabel = hasEndJumpFrame(nodes) ? findOrMakeEndLabel(nodes) : makeEndJumpFrame(nodes, sig, source);

        INSTRUCTION_LOOP:
        for (AbstractInsnNode current = nodes.getFirst(); current != null; current = current.getNext()) {
            switch (current.getOpcode()) {
                case Opcodes.IRETURN:
                case Opcodes.FRETURN:
                case Opcodes.ARETURN:
                    nodes.set(current, current = new InsnNode(Opcodes.POP));
                    break;

                case Opcodes.LRETURN:
                case Opcodes.DRETURN:
                    nodes.set(current, current = new InsnNode(Opcodes.POP2));
                    break;

                case Opcodes.RETURN:
                    nodes.set(current, current = new JumpInsnNode(Opcodes.GOTO, endLabel));
                    // Fallthrough

                default:
                    continue INSTRUCTION_LOOP;
            }

            nodes.insert(current, current = new JumpInsnNode(Opcodes.GOTO, endLabel));
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


    private static void adjustArgument(MethodNode node, LocalVariableNode varNode, boolean backward, boolean insert) {
        if (backward) {
            // Finds first label or creates it
            LabelNode firstLabel = findLabelBeforeReturn(node.instructions.getFirst(), AbstractInsnNode::getNext);

            // No label found before a return: create one
            if (firstLabel == null)
                node.instructions.insert(firstLabel = new LabelNode());

            varNode.start = firstLabel;
        } else {
            // Finds last label or creates it
            LabelNode lastLabel = findLabelBeforeReturn(node.instructions.getLast(), AbstractInsnNode::getPrevious);

            // No label found after a return: create one
            if (lastLabel == null)
                node.instructions.add(lastLabel = new LabelNode());

            varNode.end = lastLabel;
        }

        if (insert) {
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
        }
    }

    protected static void adjustFramesForRetVar(InsnList nodes, int argc) {
        boolean isFirst = true;
        for (AbstractInsnNode node = nodes.getFirst(); node != null; node = node.getNext())
            if (node instanceof FrameNode) {
                if (isFirst) {
                    isFirst = false;

                    if (((FrameNode) node).type == Opcodes.F_APPEND) {
                        List<Object> append = new ArrayList<>(((FrameNode) node).local);
                        append.add(0, Opcodes.TOP);
                        ((FrameNode) node).local = append;
                    }
                }

                if (((FrameNode) node).type == Opcodes.F_FULL) {
                    List<Object> append = new ArrayList<>(((FrameNode) node).local);
                    append.add(argc, Opcodes.TOP);
                    ((FrameNode) node).local = append;
                }
            }
    }


    /**
     * Adapts a grafted method instruction node to fit its surrogate
     * @param node Grafted method instruction node
     * @param source The {@link GraftSource} from which the instruction node will be adapted
     */
    protected void adaptMethodInsn(MethodInsnNode node, GraftSource source, MethodNode sourceMethod) {
        if (node.owner.equals(source.getTypeName())) {
            final MethodNode injected = source.getInjectedMethod(node.name, node.desc);
            if (injected != null) {
                node.owner = this.target.name;
                node.name = source.getMethodTargetName(injected);
                node.desc = adaptMethodSignature(node.desc, source);
            }
        } else if (node.owner.equals("dev/w1zzrd/asm/Directives")) { // ASM target directives
            if (node.name.equals(Directives.directiveNameByTarget(DirectiveTarget.TargetType.CALL_SUPER))) {
                // We're attempting to redirect a call to a superclass
                for (AbstractInsnNode prev = node.getPrevious(); prev != null; prev = prev.getPrevious()) {

                    if (prev instanceof MethodInsnNode &&
                            (((MethodInsnNode) prev).owner.equals(target.name) ||
                                    ((MethodInsnNode) prev).owner.equals(source.getTypeName()))) {
                        // Point method owner to superclass
                        ((MethodInsnNode) prev).owner = target.superName;

                        // Since we're calling super, we want to make it a special call
                        if (prev.getOpcode() == Opcodes.INVOKEVIRTUAL)
                            ((MethodInsnNode) prev).setOpcode(Opcodes.INVOKESPECIAL);
                    } else if (prev instanceof FieldInsnNode &&
                            (((FieldInsnNode) prev).owner.equals(target.name) ||
                                    ((FieldInsnNode) prev).owner.equals(source.getTypeName()))) {
                        // Just change the field we're accessing to the targets superclass' field
                        ((FieldInsnNode) prev).owner = target.superName;
                    } else {
                        continue;
                    }

                    return;
                }

                throw new RuntimeException(String.format("Could not locate a target for directive %s", node.name));
            } else if (node.name.equals(Directives.directiveNameByTarget(DirectiveTarget.TargetType.CALL_ORIGINAL))) {
                // We want to redirect execution to the original method code
                // The callOriginal method returns void, so the stack should be empty at this point

                InsnList insnList = sourceMethod.instructions;

                // If we already have a final frame, there's no need to add one
                LabelNode endLabel = hasEndJumpFrame(insnList) ?
                        findOrMakeEndLabel(insnList) :
                        makeEndJumpFrame(insnList, new MethodSignature(sourceMethod.desc), sourceMethod);

                AbstractInsnNode jumpInsn = new JumpInsnNode(Opcodes.GOTO, endLabel);
                insnList.set(node, jumpInsn);

                MethodSignature sig = new MethodSignature(sourceMethod.desc);
                final Class<?>[] ignoredNodes = {LineNumberNode.class, LabelNode.class, FrameNode.class};
                AbstractInsnNode afterJump = getNextNode(jumpInsn, ignoredNodes);

                if (!sig.getRet().isVoidType()) {
                    // Now we want to remove extraneous (unreachable) return instructions
                    afterJump = getNextNode(afterJump, ignoredNodes);

                    // This should remove extraneous return instructions, along with any constants pushed to the stack
                    if (afterJump.getOpcode() >= Opcodes.IRETURN && afterJump.getOpcode() < Opcodes.RETURN) {
                        insnList.remove(afterJump);
                        insnList.remove(getNextNode(jumpInsn, ignoredNodes));
                    }
                } else if (afterJump.getOpcode() == Opcodes.RETURN) {
                    // This should just remove the extraneous RETURN instruction
                    insnList.remove(afterJump);
                }
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

    protected void adaptFrameNode(FrameNode node, GraftSource source) {
        adaptFrameTypes(node.stack, source);
        adaptFrameTypes(node.local, source);
    }


    protected void adaptFrameTypes(List<Object> types, GraftSource source) {
        if (types == null)
            return;

        final String sourceTypeName = "L"+source.getTypeName()+";";
        final String targetTypeName = "L"+target.name+";";

        for (int i = 0; i < types.size(); ++i) {
            if (types.get(i) instanceof Type) {
                Type t = (Type) types.get(i);

                if (t.getSort() == Type.OBJECT && sourceTypeName.equals(t.getInternalName()))
                    types.set(i, Type.getType(targetTypeName));
                else if (t.getSort() == Type.METHOD)
                    types.set(i, Type.getMethodType(adaptMethodSignature(t.getDescriptor(), source)));
            } else if (types.get(i) instanceof String && source.getTypeName().equals(types.get(i)))
                types.set(i, target.name);
        }
    }

    protected static List<Object> makeFrameLocals(TypeSignature... sigs) {
        ArrayList<Object> local = new ArrayList<>();

        for (TypeSignature sig : sigs)
            if (sig.isPrimitive())
                switch (sig.getSig().charAt(0)) {
                    case 'B':
                    case 'Z':
                    case 'C':
                    case 'I':
                        local.add(Opcodes.INTEGER);
                        break;

                    case 'F':
                        local.add(Opcodes.FLOAT);
                        break;

                    case 'J':
                        if (sig.isTop())
                            local.add(Opcodes.TOP);
                        else
                            local.add(Opcodes.LONG);
                        break;

                    case 'D':
                        if (sig.isTop())
                            local.add(Opcodes.TOP);
                        else
                            local.add(Opcodes.DOUBLE);
                        break;
                }
            else if (sig.isNull()) local.add(Opcodes.NULL);
            else if (sig.isUninitialized()) local.add(Opcodes.UNINITIALIZED_THIS);
            else if (sig.isArray()) local.add(sig.getSig());
            else local.add(sig.getSig().substring(1, sig.getSig().length() - 1));

        return local;
    }

    protected void adaptFieldInsn(FieldInsnNode node, GraftSource source) {
        if (node.owner.equals(source.getTypeName()))
            node.owner = target.name;
    }

    protected void adaptInvokeDynamicInsn(InvokeDynamicInsnNode insn, GraftSource source) {
        if (insn.bsmArgs[1] instanceof Handle && ((Handle) insn.bsmArgs[1]).getOwner().equals(source.getTypeName())) {
            // We have an INVOKEDYNAMIC to a method in the graft source class
            // The target has to be injected into the target
            Handle handle = (Handle) insn.bsmArgs[1];

            for (MethodNode mNode : target.methods)
                if (mNode.name.equals(handle.getName()) && mNode.desc.equals(handle.getDesc()))
                    return; // The target has already been injected

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
            insn.bsmArgs[1] = new Handle(
                    handle.getTag(),
                    target.name,
                    handle.getName(),
                    adaptMethodSignature(handle.getDesc(), source)
            );
        }
    }

    /**
     * Replaces any references to the graft source type with the target type
     * @param desc Method descriptor to adapt
     * @param source {@link GraftSource} to replace references to
     * @return Method signature with references to target type in place of graft source type
     */
    protected String adaptMethodSignature(String desc, GraftSource source) {
        TypeSignature graftSig = new TypeSignature("L"+source.getTypeName()+";");
        MethodSignature sig = new MethodSignature(desc);
        for (int i = 0; i < sig.getArgCount(); ++i)
            if (sig.getArg(i).getArrayAtomType().equals(graftSig))
                sig.setArg(
                        i,
                        new TypeSignature(
                                "L"+target.name+";",
                                sig.getArg(i).getArrayDepth(),
                                false
                        )
                );

        if (sig.getRet().getArrayAtomType().equals(graftSig))
            sig.setRet(new TypeSignature(
                    "L"+target.name+";",
                    sig.getRet().getArrayDepth(),
                    false
            ));

        return sig.toString();
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

    protected static AbstractInsnNode getNextNode(AbstractInsnNode node, Class<?>... skipTypes) {
        return traverseNode(node, AbstractInsnNode::getNext, skipTypes);
    }

    protected static AbstractInsnNode getPreviousNode(AbstractInsnNode node, Class<?>... skipTypes) {
        return traverseNode(node, AbstractInsnNode::getPrevious, skipTypes);
    }

    private static AbstractInsnNode traverseNode(AbstractInsnNode node, INodeTraversal traversal, Class<?>[] skipTypes) {
        TRAVERSAL:
        for (AbstractInsnNode trav = traversal.traverse(node); trav != null; trav = traversal.traverse(trav)) {
            for (Class<?> cls : skipTypes)
                if (trav.getClass().equals(cls))
                    continue TRAVERSAL;
            return trav;
        }
        return null;
    }



    @SuppressWarnings("unused") // Used for debugging
    public static java.util.List<? extends AbstractInsnNode> dumpInsns(MethodNode node) {
        return Arrays.asList(node.instructions.toArray());
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

    protected static LocalVariableNode getVarAt(List<LocalVariableNode> varNodes, int index) {
        return varNodes.stream().filter(it -> it.index == index).findFirst().orElse(null);
    }


    protected static @Nullable LabelNode findLabelBeforeReturn(AbstractInsnNode start, INodeTraversal traverse) {
        for (AbstractInsnNode cur = start; cur != null; cur = traverse.traverse(cur))
            if (cur instanceof LabelNode) // Traversal hit label
                return (LabelNode) cur;
            else if (cur.getOpcode() >= Opcodes.IRETURN && cur.getOpcode() <= Opcodes.RETURN) // Traversal hit return
                return null;

        return null; // Nothing was found
    }

    protected static boolean isStatic(MethodNode node) {
        return (node.access & Opcodes.ACC_STATIC) != 0;
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
