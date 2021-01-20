package dev.w1zzrd.asm.exception;

public class TypeSignatureParseException extends IllegalArgumentException {
    public TypeSignatureParseException() {
    }

    public TypeSignatureParseException(String s) {
        super(s);
    }

    public TypeSignatureParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public TypeSignatureParseException(Throwable cause) {
        super(cause);
    }
}
