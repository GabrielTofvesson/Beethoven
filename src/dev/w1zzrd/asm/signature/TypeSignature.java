package dev.w1zzrd.asm.signature;

import dev.w1zzrd.asm.exception.SignatureInstanceMismatchException;
import dev.w1zzrd.asm.exception.TypeSignatureParseException;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

public class TypeSignature {
    private final String sig;
    private final int arrayDepth;

    private final TypeModifier modifier;
    private final MethodSignature dynamicRef;

    public TypeSignature(String sig, int reportedArrayDepth, boolean isUninitialized) {
        // Signature cannot be an empty string
        if (sig.length() == 0)
            throw new TypeSignatureParseException("Signature cannot be blank!");

        modifier = TypeModifier.UNINITIALIZED.iff(isUninitialized);
        dynamicRef = null;

        StringBuilder builder = new StringBuilder(reportedArrayDepth + 1);
        char[] fill = new char[reportedArrayDepth];
        Arrays.fill(fill, '[');
        builder.append(fill);

        final int len = sig.length();


        // Compute the nesting depth of array types
        int arrayDepth = 0;
        while (sig.charAt(arrayDepth) == '[')
            if (++arrayDepth == len)
                throw new TypeSignatureParseException("Array signature of blank type is not valid!");

        this.arrayDepth = arrayDepth + reportedArrayDepth;

        // Resolve signature from type identifier
        switch (Character.toUpperCase(sig.charAt(arrayDepth))) {
            // Primitive type
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
            case 'J':
            case 'F':
            case 'D': {
                this.sig = builder.append(sig.toUpperCase()).toString();
                return;
            }

            // Special void return type
            case 'V': {
                // Type "[V" cannot exist, for example
                if (reportedArrayDepth + arrayDepth > 0)
                    throw new TypeSignatureParseException("Void type cannot have an array depth!");

                this.sig = sig.toUpperCase();
                return;
            }

            // Object type
            case 'L': {
                // Unterminated type signature
                if (sig.charAt(sig.length() - 1) != ';')
                    break;

                this.sig = builder.append(sig).toString();
                return;
            }
        }

        throw new TypeSignatureParseException(String.format("Unknown type signature \"%s\"", sig));
    }

    public TypeSignature(String sig) {
        this(sig, 0,  false);
    }

    /**
     * Create a Top value signature
     * @param primitive Primitive type internal name (V, J or D for Top types)
     * @param isTop Whether or not this is a Top type (only valid for 64-bit types J and D or as delimiter type V)
     */
    public TypeSignature(@Nullable Character primitive, boolean isTop) {
        if (primitive != null) {
            switch (Character.toUpperCase(primitive)) {
                case 'J':
                case 'D':
                case 'V':
                    break;

                case 'I':
                case 'F':
                case 'S':
                case 'Z':
                case 'C':
                case 'B':
                    if (isTop)
                        throw new TypeSignatureParseException(String.format(
                                "Primitive type signature %c cannot have a Top value. To declare a Top delimiter, use 'V'",
                                primitive
                        ));
                    break;

                default:
                    throw new TypeSignatureParseException(String.format(
                            "Unknown primitive signature %c",
                            primitive
                    ));
            }
        }
        this.sig = primitive == null ? "V" : Character.toString(Character.toUpperCase(primitive));
        modifier = TypeModifier.TOP.iff(isTop);
        dynamicRef = null;
        this.arrayDepth = 0;
    }

    public TypeSignature(MethodSignature dynamicRef) {
        modifier = TypeModifier.METHOD;
        arrayDepth = 0;
        sig = dynamicRef.toString();
        this.dynamicRef = dynamicRef;
    }

    /**
     * Represents a type signature of the JVM null verification type
     */
    public TypeSignature() {
        this.sig = "null";
        modifier = TypeModifier.NULL;
        dynamicRef = null;
        this.arrayDepth = 0;
    }

    /**
     * Get the actual signature represented by this object
     * @return The fully qualified type signature of the represented type
     */
    public String getSig() {
        return sig;
    }

    /**
     * The contained type (in the case that this is an array type). If the type represented by this object
     * is not an array, this is equivalent to calling {@link TypeSignature#getSig()}
     * @return Signature of the type contained in the array
     */
    public String getType() {
        return sig.substring(arrayDepth);
    }

    /**
     * Whether or not the type represented by this object is an array type
     * @return True if the type has an array depth greater than 0, else false
     */
    public boolean isArray() {
        return arrayDepth > 0;
    }

    /**
     * Get the type signature of the elements contained in an array type
     * @return The element type signature of the current array type signature
     */
    public TypeSignature getArrayElementType() {
        if (!isArray())
            throw new SignatureInstanceMismatchException("Attempt to get element type of non-array!");

        return new TypeSignature(sig.substring(1));
    }

    /**
     * Check whether or not this type represents a Top type.
     * @return True if it is a Top, else false
     */
    public boolean isTop() {
        return modifier == TypeModifier.TOP;
    }

    /**
     * Check if the currently represented type is a void return type
     * @return True if the signature is "V", else false
     */
    public boolean isVoidType() {
        return sig.length() == 1 && sig.charAt(0) == 'V';
    }

    /**
     * Whether or not this type signature represents a primitive type in the JVM
     * Primitive types are: Z, B, C, S, I, J, F, D, V
     * @return True if this signature is primitive, false if it represents a reference-type (object)
     */
    public boolean isPrimitive() {
        return sig.length() == 1;
    }

    /**
     * The array depth of the currently represented object. Primitives and objects have a depth of 0.
     * Array types have a depth grater than 0 dependent on the depth of the nesting.
     * @return 0 for primitives and object and 1+ for all other types
     */
    public int getArrayDepth() {
        return arrayDepth;
    }

    /**
     * Gets the amount of slots the represented type occupies in a stack frame local variable list and operand stack
     * @return 2 for (non-array) Double and Long types, 1 for everything else
     */
    public int stackFrameElementWith() {
        return isPrimitive() && (sig.charAt(0) == 'J' || sig.charAt(0) == 'D') ? 2 : 1;
    }

    /**
     * Get a string representation of the represented type
     * @return The exact internal string representation of the signature
     */
    @Override
    public String toString() {
        return sig;
    }

    /**
     * Checks if this object is semantically equivalent to the given object
     * @param other The object to compare to
     * @return True iff {@code other} is an instance of {@link TypeSignature} and the signatures are identical
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof TypeSignature &&
                ((TypeSignature)other).sig.equals(sig) &&
                ((TypeSignature) other).modifier == modifier;
    }

    /**
     * Computes the hashcode of this object. This mimics the equivalence specified by
     * {@link TypeSignature#equals(Object)} by simply being the hashcode of the signature string
     * @return The hashcode of the represented signature string
     */
    @Override
    public int hashCode() {
        return Objects.hash(sig, modifier);
    }


    private enum TypeModifier {
        NONE, TOP, UNINITIALIZED, NULL, METHOD;

        public TypeModifier iff(boolean condition) {
            return condition ? this : NONE;
        }
    }
}
