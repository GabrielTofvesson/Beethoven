package dev.w1zzrd.asm;

import dev.w1zzrd.asm.analysis.AsmAnnotation;
import dev.w1zzrd.asm.exception.MethodNodeResolutionException;
import dev.w1zzrd.asm.exception.SignatureCheckException;
import dev.w1zzrd.asm.exception.SignatureInstanceMismatchException;
import dev.w1zzrd.asm.signature.MethodSignature;
import dev.w1zzrd.asm.signature.TypeSignature;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Handle;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static jdk.internal.org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

public class Combine {
    private final ArrayList<DynamicSourceUnit> graftSources = new ArrayList<>();

    private final ClassNode target;


    public Combine(ClassNode target) {
        this.target = target;
    }

    public void inject(MethodNode node, GraftSource source) {
        final AsmAnnotation<Inject> annotation = source.getMethodInjectAnnotation(node);

        switch ((InPlaceInjection)annotation.getEnumEntry("value")) {
            case INSERT: // Explicitly insert a *new* method
                insert(node, source);
                break;
            case REPLACE: // Explicitly replace an *existing* method
                replace(node, source, true);
                break;
            case INJECT: // Insert method by either replacing an existing method or inserting a new method
                if (initiateGrafting(node, source))
                    return;

                insertOrReplace(node, source);

                finishGrafting(node, source);
                break;
            case AFTER: // Inject a method's instructions after the original instructions in a given method
                append(node, source);
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
     */
    public void append(MethodNode extension, GraftSource source) {
        if (initiateGrafting(extension, source))
            return;

        final MethodResolution resolution = resolveMethod(extension, source, true);
        boolean acceptReturn = resolution.acceptReturn;
        adaptMethod(extension, source);

        // Get the method signatures so we know what we're working with local-variable-wise ;)
        final MethodSignature msig = new MethodSignature(resolution.node.desc);
        final MethodSignature xsig = new MethodSignature(extension.desc);

        // Get total argument count, including implicit "this" argument
        final int graftArgCount = xsig.getArgCount() + (isStatic(extension) ? 0 : 1);
        final int targetArgCount = msig.getArgCount() + (isStatic(resolution.node) ? 0 : 1);

        // If graft method cares about the return value of the original method, i.e. accepts it as an extra "argument"
        if (acceptReturn && !msig.getRet().isVoidType()) {
            //noinspection OptionalGetWithoutIsPresent
            LocalVariableNode retVar = extension.localVariables
                    .stream()
                    .filter(it -> it.index == graftArgCount - 1)
                    .findFirst()
                    .get();

            // Inject return variable
            adjustArgument(resolution.node, retVar, true, true);

            // Handle retvar specially
            extension.localVariables.remove(retVar);

            // Make space in the original frames for the return var
            // This isn't an optimal solution, but it works for now
            adjustFramesForRetVar(resolution.node.instructions, targetArgCount);

            // Replace return instructions with GOTOs to the last instruction in the list
            // Return values are stored in retVar
            storeAndGotoFromReturn(resolution.node, resolution.node.instructions, retVar.index, xsig);
        } else {
            // If we don't care about the return value from the original, we can replace returns with pops
            popAndGotoFromReturn(resolution.node, resolution.node.instructions, xsig);
        }

        List<LocalVariableNode> extVars = getVarsOver(extension.localVariables, xsig.getArgCount());

        // Add extension vars to target
        resolution.node.localVariables.addAll(extVars);

        // Add extension instructions to instruction list
        resolution.node.instructions.add(extension.instructions);

        // Make sure we extend the scope of the original method arguments
        for (int i = 0; i < targetArgCount; ++i)
            adjustArgument(resolution.node, getVarAt(resolution.node.localVariables, i), false, false);

        // Recompute maximum variable count
        resolution.node.maxLocals = Math.max(
                Math.max(
                        targetArgCount + 1,
                        graftArgCount + 1
                ),
                resolution.node.localVariables
                        .stream()
                        .map(it -> it.index)
                        .max(Comparator.comparingInt(a -> a)).orElse(0) + 1
        );

        // Recompute maximum stack size
        resolution.node.maxStack = Math.max(resolution.node.maxStack, extension.maxStack);

        // Merge try-catch blocks
        resolution.node.tryCatchBlocks.addAll(extension.tryCatchBlocks);
        // Exception list not merged to maintain original signature

        finishGrafting(extension, source);
    }

    public void prepend(MethodNode extension, GraftSource source) {
        if (initiateGrafting(extension, source))
            return;

        final MethodNode target = resolveMethod(extension, source, false).node;
        adaptMethod(extension, source);

        MethodSignature sig = new MethodSignature(extension.desc);

        target.localVariables.addAll(getVarsOver(extension.localVariables, sig.getArgCount()));
        extension.instructions.add(target.instructions);

        target.instructions = extension.instructions;

        // Extend argument scope to cover prepended code
        for (int i = 0; i < sig.getArgCount(); ++i)
            adjustArgument(target, getVarAt(target.localVariables, i), true, false);

        target.tryCatchBlocks.addAll(extension.tryCatchBlocks);
        // Exception list not merged to maintain original signature

        finishGrafting(extension, source);
    }

    public void replace(MethodNode inject, GraftSource source, boolean preserveOriginalAccess) {
        if (initiateGrafting(inject, source))
            return;

        final MethodNode remove = checkMethodExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject, false));
        ensureMatchingSignatures(remove, inject, Opcodes.ACC_STATIC);
        if (preserveOriginalAccess)
            copySignatures(remove, inject, Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);

        insertOrReplace(inject, source);

        finishGrafting(inject, source);
    }

    public void insert(MethodNode inject, GraftSource source) {
        if (initiateGrafting(inject, source))
            return;

        checkMethodNotExists(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject, false));
        insertOrReplace(inject, source);

        finishGrafting(inject, source);
    }

    protected void insertOrReplace(MethodNode inject, GraftSource source) {
        MethodNode replace = findMethodNode(source.getMethodTargetName(inject), source.getMethodTargetSignature(inject, false));

        if (replace != null)
            this.target.methods.remove(replace);

        adaptMethod(inject, source);

        this.target.methods.add(inject);
    }

    public void inject(FieldNode field, GraftSource source) {
        if (field.desc.equals(source.getTypeName()))
            field.desc = target.name;

        // Remove existing field with same name
        for (FieldNode node : target.fields)
            if (node.name.equals(field.name)) {
                target.fields.remove(node);
                break;
            }

        target.fields.add(field);
    }

    public void setSuperClass(String superDesc) {
        // Theoretically usable for redefining Object (under a new ClassLoader)
        if (superDesc == null) {
            target.superName = null;
            return;
        }

        if (new TypeSignature(superDesc).isPrimitive())
            throw new SignatureCheckException("Superclass cannot be primitive: "+superDesc);

        target.superName = superDesc;
    }

    public String getSuperclass() {
        return target.superName;
    }

    public TypeSignature[] getInterfaces() {
        return target.interfaces.stream().map(TypeSignature::new).toArray(TypeSignature[]::new);
    }

    public boolean removeInterface(String interfaceDesc) {
        return target.interfaces.remove(interfaceDesc);
    }

    public void addInterface(String interfaceDesc) {
        if (!target.interfaces.contains(interfaceDesc))
            target.interfaces.add(interfaceDesc);
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

    public ClassNode getClassNode() {
        return target;
    }

    /**
     * Prepares a {@link MethodNode} for grafting on to a given method and into the targeted  {@link ClassNode}
     * @param node Node to adapt
     * @param source The {@link GraftSource} from which the node will be adapted
     */
    protected void adaptMethod(MethodNode node, GraftSource source) {
        // Adapt instructions
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) insn = adaptMethodInsn((MethodInsnNode) insn, source, node);
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
    protected AbstractInsnNode adaptMethodInsn(MethodInsnNode node, GraftSource source, MethodNode sourceMethod) {
        if (node.owner.equals(source.getTypeName())) {
            //final MethodNode injected = source.getInjectedMethod(node.name, node.desc);
            //if (injected != null) {
                node.owner = this.target.name;
                //node.name = source.getMethodTargetName(injected);
                //node.desc = adaptMethodSignature(node.desc, source);
            //}
            return node;
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

                    sourceMethod.instructions.remove(node);

                    return prev;
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

                return jumpInsn;
            }
        }

        return node;
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
    protected final MethodNode checkMethodExists(String name, MethodSignature descriptor) {
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
    protected MethodNode findMethodNode(String name, MethodSignature desc) {
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

    protected static List<LocalVariableNode> getVarsOver(List<LocalVariableNode> varNodes, int minIndex) {
        return varNodes.stream().filter(it -> it.index >= minIndex).collect(Collectors.toList());
    }

    protected static LocalVariableNode getVarAt(List<LocalVariableNode> varNodes, int index) {
        return varNodes.stream().filter(it -> it.index == index).findFirst().orElse(null);
    }


    protected static LabelNode findLabelBeforeReturn(AbstractInsnNode start, INodeTraversal traverse) {
        for (AbstractInsnNode cur = start; cur != null; cur = traverse.traverse(cur))
            if (cur instanceof LabelNode) // Traversal hit label
                return (LabelNode) cur;
            else if (cur.getOpcode() >= Opcodes.IRETURN && cur.getOpcode() <= Opcodes.RETURN) // Traversal hit return
                return null;

        return null; // Nothing was found
    }

    protected MethodResolution resolveMethod(MethodNode inject, GraftSource source, boolean allowAcceptRet) {
        AsmAnnotation<Inject> annot = AsmAnnotation.getAnnotation(Inject.class, inject.visibleAnnotations);
        if (!allowAcceptRet && (Boolean)annot.getEntry("acceptOriginalReturn"))
            throw new MethodNodeResolutionException(String.format(
                    "Method %s marked as accepting original return, but injection strategy prohibits this!",
                    inject.name
            ));

        boolean acceptRet = annot.getEntry("acceptOriginalReturn");

        String sig = adaptMethodSignature(source.getMethodTarget(inject), source);
        final MethodSignature mSig = new MethodSignature(sig);

        final String targetName = source.getMethodTargetName(inject);

        // Collect possible method candidates by name
        List<MethodNode> candidates = this.target
                .methods
                .stream()
                .filter(it -> it.name.equals(targetName))
                .collect(Collectors.toList());

        // No candidates match the base criteria
        if (candidates.isEmpty())
            throw new MethodNodeResolutionException(String.format(
                    "Cannot find and target candidates for method %s%s",
                    inject.name,
                    inject.desc
            ));

        // If we accept original return value, target with not contain final argument
        if (acceptRet) {
            if (!mSig.getRet().equals(mSig.getArg(mSig.getArgCount() - 1)))
                throw new MethodNodeResolutionException(String.format(
                        "Return value must match final method argument when accepting return: %s%s",
                        inject.name,
                        inject.desc
                ));
            sig = mSig.withoutLastArg().toString();
        }

        final String findSig = sig;
        final List<MethodNode> cand = candidates;
        candidates = candidates.stream().filter(it -> it.desc.equals(findSig)).collect(Collectors.toList());

        // We have no candidates
        if (candidates.isEmpty()) {
            // If no candidates were found for the explicitly declared signature,
            // check if accepting original return value was implied
            if (!acceptRet &&
                    allowAcceptRet &&
                    mSig.getArgCount() > 0 &&
                    mSig.getRet().equals(mSig.getArg(mSig.getArgCount() - 1))) {
                // Search for method without the implied return value argument
                final String fSig = mSig.withoutLastArg().toString();
                candidates = cand.stream().filter(it -> it.desc.equals(fSig)).collect(Collectors.toList());

                // Do we have a match?
                if (candidates.size() == 1)
                    return new MethodResolution(candidates.get(0), true);
            }

            throw new MethodNodeResolutionException(String.format(
                    "Cannot find and target candidates for method %s%s",
                    inject.name,
                    inject.desc
            ));
        }

        // If we have a candidate, it will have a specific name and signature
        // Therefore there cannot be more than one candidate by JVM convention
        return new MethodResolution(candidates.get(0), acceptRet && allowAcceptRet);
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

    private static class MethodResolution {
        public final MethodNode node;
        public final boolean acceptReturn;

        public MethodResolution(MethodNode node, boolean acceptReturn) {
            this.node = node;
            this.acceptReturn = acceptReturn;
        }
    }
}
