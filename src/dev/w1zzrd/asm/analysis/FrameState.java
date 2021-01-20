package dev.w1zzrd.asm.analysis;

import dev.w1zzrd.asm.exception.StateAnalysisException;
import dev.w1zzrd.asm.signature.MethodSignature;
import dev.w1zzrd.asm.signature.TypeSignature;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.*;
import org.jetbrains.annotations.Nullable;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Predicate;

/**
 * Method frame state analysis class.
 * Ideally, this should allow for instruction optimization when weaving methods.
 * Additionally, this could theoretically enable code to be woven in-between original instructions
 */
public class FrameState {
    /**
     * Stack clobbering pushed values after an instruction is invoked.
     * (See {@link jdk.internal.org.objectweb.asm.Frame#SIZE})<br>
     * Key:<br>
     *   ? No change<br>
     *   X Requires special attention<br>
     *   L Object<br>
     *   I int<br>
     *   J long<br>
     *   F float<br>
     *   D double<br>
     */
    private static final String STACK_CLOBBER_PUSH =
            "?LIIIIIIIJJFFFDDIIXXXIJFDLIIIIJJJJFFFFDDDDLLLLIJFDLIII???????????????????????????????????XXXXXX?IJFDIJFDIJFDIJFDIJFDIJFDIJIJIJIJIJIJXJFDIFDIJDIJFIIIIIIII?????????????????????????X?X?XXXXXLLLI?LI??XL????";

    /**
     * Stack clobbering popped values when an instruction is invoked.
     * (See {@link jdk.internal.org.objectweb.asm.Frame#SIZE})<br>
     * Key:<br>
     *   ? None<br>
     *   X Requires special attention<br>
     *   $ Cat1 computational type<br>
     *   L Object<br>
     *   I int<br>
     *   J long<br>
     *   F float<br>
     *   D double<br>
     *   S int/float<br>
     *   W long/double<br>
     *   C int, int<br>
     *   V long, long<br>
     *   B float, float<br>
     *   N double, double<br>
     *   M object, int<br>
     *   0 object, object<br>
     *   1 object, int, int<br>
     *   2 object, int, long<br>
     *   3 object, int, float<br>
     *   4 object, int, double<br>
     *   5 object, int, object<br>
     *   K Cat1, Cat1<br>
     * <br>
     * Cat1 computational types are, according to the JVM8 spec, essentially all 32-bit types (any type that occupies 1 stack slot)
     */
    private static final String STACK_CLOBBER_POP  =
            "??????????????????????????????????????????????MMMMMMMMIJFDLIIIIJJJJFFFFDDDDLLLL12345111$K$$$XXXKCVBNCVBNCVBNCVBNCVBNCVBNCVCVCVCVCVCV?IIIJJJFFFDDDIIIVBBNNIIIIIICCCCCC00?????IJFDL??XLXXXXXX?IILLLLLLXXLL??";


    private final Stack<TypeSignature> stack = new Stack<>();
    private final ArrayList<TypeSignature> locals = new ArrayList<>();
    private final int stackSize;

    private FrameState(AbstractInsnNode targetNode, List<TypeSignature> constants) {
        AbstractInsnNode first = targetNode, tmp;

        // Computation is already O(n), no need to accept first instruction as an argument
        while ((tmp = first.getPrevious()) != null)
            first = tmp;

        // Now we traverse backward to select ONE possible sequence of instructions that may be executed
        // This lets us simulate the stack for this sequence. This only works because we don't consider conditionals
        // Because we ignore conditionals and because we have assisting FrameNodes, we sidestep the halting problem
        // Since we're traversing backward, the latest instruction to be read will be the earliest to be executed
        Stack<AbstractInsnNode> simulate = new Stack<>();
        for (AbstractInsnNode check = targetNode; check != null; check = check.getPrevious()) {
            // If the node we're checking is a label, find the earliest jump to it
            // This assumes that no compiler optimizations have been made based on multiple values at compile time
            // since we can't trivially predict branches, but I don't think the java compiler does either, so meh
            if (check instanceof LabelNode) {
                // Labels don't affect the stack, so we can safely ignore them
                JumpInsnNode jump = findEarliestJump((LabelNode) check);
                if (jump == null)
                    continue;

                check = jump;
            }

            // No need to check line numbers in a simulation
            if (check instanceof LineNumberNode)
                continue;

            // Add instruction to simulation list
            simulate.add(check);

            // No need to simulate the state before a full frame: this is kinda like a "checkpoint" in the frame state
            if (check instanceof FrameNode && ((FrameNode) check).type == Opcodes.F_FULL)
                break;
        }

        int stackSize = 0;

        // We now have a proposed set of instructions that might run if the instructions were to be called
        // Next, we analyse the stack and locals throughout the execution of these instructions
        while (!simulate.isEmpty()) {
            if (simulate.peek() instanceof FrameNode)
                stackSize = ((FrameNode) simulate.peek()).stack.size();

            updateFrameState(simulate.pop(), stack, locals, constants);
        }

        this.stackSize = stackSize;

        // The stack and locals are now in the state they would be in after the target instruction is hit
        // QED or something...


        /*
         * NOTE: This code analysis strategy assumes that the program analyzed follows the behaviour of any regular JVM
         * program. This will, for example, fail to predict the state of the following set of (paraphrased)
         * instructions:
         *
         *
         * METHOD START:
         * 1:  LOAD 1
         * 2:  JUMP TO LABEL "X" IF LOADED VALUE == 0   // if(1 == 0)
         * 3:  PUSH 2
         * 4:  PUSH 69
         * 5:  PUSH 420
         * 6:  LABEL "X"
         * 7:  POP          <----- Analysis requested of this node
         *
         *
         * This FrameState method will (falsely) predict that the stack is empty and that the POP will fail, since it
         * will trace execution back to the jump at line (2), since it cannot determine that the jump will always fail
         * and simply observes the jump as popping the value loaded at (1), thus meaning that the stack would be empty
         * at line (7). Whereas in actuality, the jump will fail and the stack will therefore contain three values.
         *
         * This is because a normal Java program would not allow this arrangement of instructions, since it would entail
         * a split in the state of the stack based on the outcome of the conditional jump. I don't know of any JVM
         * language that *would* produce this behaviour (and I'm 90% sure the JVM would complain about the lack of
         * FrameNodes in the above example), but if the FrameState *does* encounter code compiled by such a language,
         * this code CANNOT predict the state of such a stack and incorrect assumptions about the state of the stack may
         * occur.
         *
         * Also note that this kind of behaviour cannot be predicted due to the halting problem, so any program which
         * exhibits the aforementioned behaviour is inherently unpredictable.
         */
    }

    /**
     * Attempts to find the earliest jump label referencing the given node in the instruction list
     * @param node {@link LabelNode} to find jumps referencing
     * @return A jump instruction node earlier in the instruction list or null if none could be found
     */
    private static @Nullable JumpInsnNode findEarliestJump(LabelNode node) {
        JumpInsnNode jump = null;

        // Traverse backward until we hit the beginning of the list
        for (AbstractInsnNode prev = node; prev != null; prev = prev.getPrevious())
            if (prev instanceof JumpInsnNode && ((JumpInsnNode) prev).label.equals(node))
                jump = (JumpInsnNode) prev;

        return jump;
    }

    /**
     * Updates the state of a simulated stack frame based on the effects of a given instruction. Effectively simulates
     * the instruction to a certain degree
     * @param instruction Instruction to "simulate"
     * @param stack Frame stack values
     * @param locals Frame local variables
     * @param constants Method constant pool types
     */
    private static void updateFrameState(
            AbstractInsnNode instruction,
            Stack<TypeSignature> stack,
            ArrayList<TypeSignature> locals,
            List<TypeSignature> constants
    ) {
        if (instruction instanceof FrameNode) {
            // Stack values are always updated at a FrameNode
            stack.clear();

            switch (instruction.getType()) {
                case Opcodes.F_NEW:
                case Opcodes.F_FULL:
                    // Since this is a full frame, we start anew
                    locals.clear();

                    // Ascertain stack types
                    appendTypes(((FrameNode) instruction).stack, stack, true);

                    // Ascertain local types
                    appendTypes(((FrameNode) instruction).local, locals, false);
                    break;

                case Opcodes.F_APPEND:
                    appendTypes(((FrameNode) instruction).local, locals, false);
                    break;

                case Opcodes.F_SAME1:
                    appendTypes(((FrameNode) instruction).stack, stack, true);
                    break;

                case Opcodes.F_CHOP:
                    List<Object> local = ((FrameNode) instruction).local;
                    if (local != null)
                        while (local.size() > locals.size())
                            locals.remove(locals.size() - 1);
                    break;
            }
        } else clobberStack(instruction, stack, locals, constants);
    }

    /**
     * Parse and append raw frame type declarations to the end of the given collection
     * @param types Raw frame types to parse
     * @param appendTo Collection to append types to
     * @param skipNulls Whether or not to short-circuit parsing when a null-valued type is found
     */
    private static void appendTypes(List<Object> types, List<TypeSignature> appendTo, boolean skipNulls) {
        if (types == null) return;

        for (Object o : types)
            if (o == null && skipNulls) break;
            else appendTo.add(o == null ? null : parseFrameSignature(o));
    }

    /**
     * Determine the {@link TypeSignature} of stack/local type declaration
     * @param o Type to parse
     * @return {@link TypeSignature} representing the given type declaration
     */
    private static TypeSignature parseFrameSignature(Object o) {
        if (o instanceof String) // Fully qualified type
            return new TypeSignature("L"+o+";");
        else if (o instanceof Integer) { // Primitive
            switch ((int)o) {
                case 0: // Top
                    return new TypeSignature('V', true);
                case 1: // Int
                    return new TypeSignature("I");
                case 2: // Float
                    return new TypeSignature("F");
                case 3: // Double
                    return new TypeSignature("D");
                case 4: // Long
                    return new TypeSignature("J");
                case 5: // Null
                    return new TypeSignature();
            }
        } else if (o instanceof Label) {
            return new TypeSignature("Ljava/lang/Object;", 0, true);
        }

        throw new StateAnalysisException(String.format("Could not determine type signature for object %s", o));
    }

    /**
     * Simulate stack-clobbering effects of invoking a given instruction with a given frame state
     * @param insn Instruction to simulate
     * @param stack Frame stack values
     * @param locals Frame local variables
     */
    private static void clobberStack(
            AbstractInsnNode insn,
            List<TypeSignature> stack,
            List<TypeSignature> locals,
            List<TypeSignature> constants
    ) {
        // Look, before you go ahead and roast my code, just know that I have a "code first, think later" mentality,
        // so this entire method was essentially throw together and structured this way before I realised what I was
        // doing. If things look like they're implemented in a dumb way, it's probably because it is. There was
        // virtually no thought behind the implementation of this method. Now... let the roasting commence

        final int opcode = insn.getOpcode();
        if (opcode >= 0 && opcode < STACK_CLOBBER_POP.length()) {
            // We have an instruction
            char pushType = STACK_CLOBBER_PUSH.charAt(opcode);
            char popType = STACK_CLOBBER_POP.charAt(opcode);

            // Yes, the switches in the conditional statements can be collapsed, but this keeps it clean (for now)
            // TODO: Collapse switch statements
            if (pushType == 'X' && popType == 'X') {
                // Complex argument and result
                // This behaviour is exhibited by 11 instructions in the JVM 8 spec
                int argCount = 0;
                switch (opcode) {
                    case Opcodes.DUP2:
                    case Opcodes.DUP2_X1:
                    case Opcodes.DUP2_X2:
                        // Actually just operates on Cat2 values, but whatever
                        stack.add(stack.size() - (opcode - 90), stack.get(stack.size() - 2));
                        stack.add(stack.size() - (opcode - 90), stack.get(stack.size() - 2));
                        break;

                    case Opcodes.INVOKEVIRTUAL:
                    case Opcodes.INVOKESPECIAL:
                    case Opcodes.INVOKEINTERFACE:
                        argCount = 1;
                    case Opcodes.INVOKESTATIC: {
                        MethodSignature msig = new MethodSignature(((MethodInsnNode)insn).desc);
                        argCount += msig.getArgCount();
                        for (int i = 0; i < argCount; ++i) {
                            // Longs and doubles pop 2 values from the stack
                            if (i < msig.getArgCount() && msig.getArg(i).stackFrameElementWith() == 2)
                                stack.remove(stack.size() - 1);

                            // All args pop at least 1 value
                            stack.remove(stack.size() - 1);
                        }

                        // For non-void methods, push return to stack
                        if (!msig.getRet().isVoidType())
                            stack.add(msig.getRet());

                        break;
                    }

                    case Opcodes.INVOKEDYNAMIC:
                        // TODO: Implement: this requires dynamic call-site resolution and injection
                        //InvokeDynamicInsnNode dyn = (InvokeDynamicInsnNode) insn;
                        break;

                    case 196: // WIDE
                        // WIDE instruction not expected in normal Java programs
                        // TODO: Implement?
                        throw new NotImplementedException();
                }

            } else if (pushType == 'X') {
                // Complex result
                // Technically IINC is classified here, but it can be ignored because this isn't a verification tool;
                // this just checks clobbering, which IINC does not do
                switch (opcode) {
                    case Opcodes.DUP:
                    case Opcodes.DUP_X1:
                    case Opcodes.DUP_X2:
                        stack.add(stack.size() - (opcode - 88), stack.get(stack.size() - 1));
                        break;

                    case Opcodes.LDC:
                    case 19:  // LDC_W
                    case 20:  // LDC2_W
                    {
                        // I'm not 100% sure this actually works for LDC_W and LDC2_W
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (ldc.cst instanceof Type) {
                            // Type objects in in context will always refer to method references, class literals or
                            // array literals
                            int sort = ((Type) ldc.cst).getSort();
                            switch (sort) {
                                case Type.OBJECT:
                                    stack.add(new TypeSignature(((Type) ldc.cst).getDescriptor()));
                                    break;

                                case Type.METHOD:
                                    stack.add(new TypeSignature(new MethodSignature(((Type) ldc.cst).getDescriptor())));
                                    break;
                            }
                        } else if (ldc.cst instanceof String){
                            // Loading a string constant, I think
                            stack.add(new TypeSignature("Ljava/lang/String;"));
                        } else {
                            // Some primitive boxed value
                            // All the boxed primitives have a public static final field TYPE declaring their unboxed
                            // type, so we just get the internal name of that field reflectively because I'm lazy
                            // TODO: Un-reflect-ify this because it can literally be solved with if-elses instead
                            try {
                                stack.add(new TypeSignature(
                                        ((Class<?>)ldc.cst.getClass().getField("TYPE").get(null)).getName()
                                ));
                            } catch (NoSuchFieldException | IllegalAccessException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        break;
                    }

                    case Opcodes.GETFIELD:
                        stack.remove(stack.size() - 1);
                    case Opcodes.GETSTATIC:
                        stack.add(new TypeSignature(((FieldInsnNode) insn).desc));
                        break;
                }
            } else if (popType == 'X') {
                // Complex argument encompasses 3 instructions
                switch (opcode) {
                    case Opcodes.PUTFIELD:
                    case Opcodes.PUTSTATIC: {
                        FieldInsnNode put = (FieldInsnNode) insn;

                        // Get type signature
                        TypeSignature sig = new TypeSignature(put.desc);

                        // If type is Long or Double, we need to pop 2 elements
                        if (sig.stackFrameElementWith() == 2)
                            stack.remove(stack.size() - 1);

                        // Pop element from stack
                        stack.remove(stack.size() - 1);

                        // If this was a non-static instruction, pop object reference too
                        if (opcode == Opcodes.PUTFIELD)
                            stack.remove(stack.size() - 1);

                        break;
                    }

                    case Opcodes.MULTIANEWARRAY: {
                        MultiANewArrayInsnNode marray = (MultiANewArrayInsnNode) insn;

                        // Pop a value for each dimension
                        for (int i = 0; i < marray.dims; ++i)
                            stack.remove(stack.size() - 1);

                        stack.add(new TypeSignature(marray.desc));
                        break;
                    }
                }
            } else {
                // Trivial-ish argument and result
                trivialPop(insn, popType, stack, locals, constants);
                trivialPush(insn, pushType, stack, locals, constants);
            }
        }
    }


    /**
     * Simulate a "trivial" instruction which pops values from the operand stack
     * @param insn Instruction to simulate pushing for
     * @param type Classification of push type
     * @param stack Simulated operand stand types
     * @param locals Simulated frame local types
     * @param constants Method constant pool
     */
    private static void trivialPop(AbstractInsnNode insn, char type, List<TypeSignature> stack, List<TypeSignature> locals, List<TypeSignature> constants) {
        // TODO: Fix type naming scheme; this is actually going to make me cry
        // Yes, the fall-throughs are very intentional
        switch (type) {
            // Pops 4 values
            case 'V':
            case 'N':
            case '2':
            case '4':
                stack.remove(stack.size() - 1);

            // Pops 3 values
            case '1':
            case '3':
            case '5':
                stack.remove(stack.size() - 1);

            // Pops 2 values
            case 'D':
            case 'J':
            case 'W':
            case 'C':
            case 'B':
            case 'M':
            case '0':
            case 'K':
                stack.remove(stack.size() - 1);

            // Pops 1 value
            case 'I':
            case 'F':
            case 'L':
            case 'S':
            case '$':
                stack.remove(stack.size() - 1);
                break;
        }
    }

    /**
     * Simulate a "trivial" instruction which pushes values to the operand stack
     * @param insn Instruction to simulate pushing for
     * @param type Classification of push type
     * @param stack Simulated operand stand types
     * @param locals Simulated frame local types
     * @param constants Method constant pool
     */
    private static void trivialPush(AbstractInsnNode insn, char type, List<TypeSignature> stack, List<TypeSignature> locals, List<TypeSignature> constants) {
        // Pushing is a bit more tricky than popping because we have to resolve types (kind of)
        switch (type) {
            case 'I':
            case 'F':
                // Push single-entry primitive
                stack.add(new TypeSignature(Character.toString(type)));
                break;

            case 'D':
            case 'J':
                // Push two-entry primitive (value + top)
                stack.add(new TypeSignature(Character.toString(type)));
                stack.add(new TypeSignature(type, true));
                break;

            case 'L':
                // Push an object type to the stack
                switch (insn.getOpcode()) {
                    case Opcodes.ACONST_NULL:
                        // Null type, I guess
                        stack.add(new TypeSignature());
                        break;

                    case Opcodes.ALOAD:
                    case 42:  // ALOAD_0
                    case 43:  // ALOAD_1
                    case 44:  // ALOAD_2
                    case 45:  // ALOAD_3
                        // Push a local variable to the stack
                        stack.add(locals.get(((VarInsnNode) insn).var));
                        break;

                    case Opcodes.AALOAD:
                        // Read an array element to the stack
                        stack.remove(stack.size() - 1); // Pop array index

                        // Pop array and push value
                        // This assumes that the popped value is an array (as it should be)
                        stack.add(stack.remove(stack.size() - 1).getArrayElementType());
                        break;

                    case Opcodes.NEW:
                        // Allocate a new object (should really be marked as uninitialized, but meh)
                        // We'll burn that bridge when we get to it or something...
                        stack.add(new TypeSignature(((TypeInsnNode) insn).desc));
                        break;

                    case Opcodes.NEWARRAY:
                        // Allocate a new, 1-dimensional, primitive array
                        stack.remove(stack.size() - 1);
                        stack.add(new TypeSignature(
                                Character.toString("ZCFDBSIJ".charAt(((IntInsnNode) insn).operand - 4)),
                                1,
                                false
                        ));
                        break;

                    case Opcodes.ANEWARRAY:
                        // Allocate a new, 1-dimensional, object array
                        stack.remove(stack.size() - 1);
                        stack.add(new TypeSignature(((TypeInsnNode) insn).desc, 1, false));
                        break;

                    case Opcodes.CHECKCAST:
                        // Cast an object to another type
                        stack.remove(stack.size() - 1);
                        stack.add(new TypeSignature(((TypeInsnNode) insn).desc));
                        break;
                }
        }
    }


    /**
     * Purely for debugging purposes. This method generates a collection of instruction names that match the given
     * functional stack-clobbering properties.<br>
     * <br>
     * For example:<br>
     *     The WIDE instruction is classified as both a complex-push and complex-pop because determining how it clobbers
     *     the stack requires determining which instruction it is wrapping and thereby what types are expected.
     *     Depending on the bytecode, the wide instruction can pop between 0 (like WIDE ILOAD) and 2 (like WIDE LSTORE)
     *     operands and may push between 0 (like WIDE ISTORE) and 2 (like WIDE DLOAD) operands or not touch the operand
     *     stack at all (like WIDE IINC).
     *
     * @param complexPush Whether or not the instructions should have non-trivial results generated by execution
     * @param complexPop Whether or not the instructions should have non-trivial argument requirements for execution
     * @param insnP An instruction-code specific predicate for fine-tuned filtering
     * @return A collection of instruction names matching the given functional properties. For instructions named
     * "Opcode<...>", please refer to the comments in {@link Opcodes} as well as the official JVM specification
     * @see <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5">JVM8 instructions spec</a>
     */
    private static List<String> getOpsByComplexity(boolean complexPush, boolean complexPop, @Nullable Predicate<Integer> insnP) {
        ArrayList<Integer> opcodes = new ArrayList<>();

        for (int i = 0; i < FrameState.STACK_CLOBBER_PUSH.length(); ++i)
            if ((FrameState.STACK_CLOBBER_PUSH.charAt(i) == 'X' == complexPush) &&
                    (FrameState.STACK_CLOBBER_POP.charAt(i) == 'X' == complexPop))
                opcodes.add(i);

        return opcodes.stream().filter(insnP == null ? it -> true : insnP).map(instrID -> {
            try {
                return java.util.Arrays
                        .stream(Opcodes.class.getFields())
                        .filter(field -> {
                            try {
                                return java.lang.reflect.Modifier.isStatic(field.getModifiers()) &&
                                        !field.getName().startsWith("ACC_") &&
                                        !field.getName().startsWith("T_") &&
                                        !field.getName().startsWith("H_") &&
                                        !field.getName().startsWith("F_") &&
                                        !field.getName().startsWith("V1_") &&
                                        field.getType().equals(int.class) &&
                                        field.get(null).equals(instrID);
                            } catch (Throwable t) {
                                throw new RuntimeException(t);
                            }
                        })
                        .map(Field::getName)
                        .findFirst()
                        .orElse(String.format("Opcode<%d>", instrID));
            } catch(Throwable t) {
                throw new RuntimeException(t);
            }
        }).collect(java.util.stream.Collectors.toList());
    }
}
