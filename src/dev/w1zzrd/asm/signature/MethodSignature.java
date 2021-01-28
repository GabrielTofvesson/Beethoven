package dev.w1zzrd.asm.signature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public class MethodSignature {
    private final TypeSignature[] args;
    private TypeSignature ret;

    public MethodSignature(String sig) {
        // Minimal signature size is 3. For example: "()V". With name, minimal length is 4: "a()V"
        if (sig.length() < 3 || (sig.charAt(0) != '(' && sig.length() < 4))
            throw new IllegalArgumentException(String.format("Invalid method signature \"%s\"", sig));

        final int len = sig.length();

        int namelen = 0;
        while (sig.charAt(namelen) != '(')
            ++namelen;

        // NOTE: namelen now points to the opening parenthesis


        // Find closing parenthesis
        int endParen = namelen + 1;
        while (sig.charAt(endParen) != ')')
            if (++endParen == len)
                throw new IllegalArgumentException(String.format("No end of argument list: \"%s\"", sig));

        // Parse argument type list
        ArrayList<TypeSignature> args = new ArrayList<>();
        int parseLen = namelen + 1;
        while (parseLen < endParen) {
            TypeSignature parsed = parseOneSignature(sig, parseLen);
            args.add(parsed);
            parseLen += parsed.getSig().length();
        }

        // Parse return type
        TypeSignature ret = parseOneSignature(sig, endParen + 1);
        if (ret.getSig().length() != len - endParen - 1)
            throw new IllegalArgumentException(String.format("Trailing characters in method signature return type: %s", sig));


        this.args = args.toArray(new TypeSignature[0]);
        this.ret = ret;
    }

    private MethodSignature(TypeSignature ret, TypeSignature[] args) {
        this.ret = ret;
        this.args = args;
    }

    private static TypeSignature parseOneSignature(String sig, int startAt) {
        final int len = sig.length();
        switch (sig.charAt(startAt)) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
            case 'J':
            case 'F':
            case 'D':
            case 'V': {
                return new TypeSignature(sig.substring(startAt, startAt + 1));
            }
            case '[': {
                for (int i = startAt + 1; i < len; ++i)
                    if (sig.charAt(i) != '[') {
                        TypeSignature nestedSig = parseOneSignature(sig, i);
                        return new TypeSignature(nestedSig.getSig(), i - startAt, false);
                    }
                break;
            }
            // Object type
            case 'L': {
                for (int i = startAt + 1; i < len; ++i)
                    if (sig.charAt(i) == ')' || sig.charAt(i) == '(')
                        throw new IllegalArgumentException("Bad type termination!");
                    else if (sig.charAt(i) == ';')
                        return new TypeSignature(sig.substring(startAt, i + 1));
                break;
            }

            case ')':
            case '(': throw new IllegalArgumentException(String.format("Unexpected token in signature \"%s\"", sig.substring(startAt)));
        }

        throw new IllegalArgumentException(String.format("Invalid type/method signature \"%s\"", sig.substring(startAt)));
    }

    public int getArgCount() {
        return args.length;
    }

    public TypeSignature[] getArgs() {
        return Arrays.copyOf(args, args.length);
    }

    public TypeSignature getArg(int index) {
        return args[index];
    }

    public void setArg(int idx, TypeSignature sig) {
        args[idx] = sig;
    }

    public MethodSignature withoutLastArg() {
        if (args.length == 0)
            throw new IndexOutOfBoundsException();

        return new MethodSignature(ret, Arrays.copyOf(args, args.length - 1));
    }

    public void setRet(TypeSignature sig) {
        ret = sig;
    }

    public TypeSignature getRet() {
        return ret;
    }

    @Override
    public String toString() {
        int size = 2;
        for (int i = 0; i < args.length; ++i)
            size += args[i].getSig().length();
        size += ret.getSig().length();

        StringBuilder builder = new StringBuilder(size);
        builder.append('(');

        for (int i = 0; i < args.length; ++i)
            builder.append(args[i].getSig());

        return builder.append(')').append(ret.getSig()).toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodSignature that = (MethodSignature) o;
        return Arrays.equals(args, that.args) && ret.equals(that.ret);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(ret);
        result = 31 * result + Arrays.hashCode(args);
        return result;
    }
}
