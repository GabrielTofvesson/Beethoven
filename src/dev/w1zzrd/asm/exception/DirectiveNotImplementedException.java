package dev.w1zzrd.asm.exception;

public class DirectiveNotImplementedException extends RuntimeException {
    public DirectiveNotImplementedException(String directiveName) {
        super("Operation not implemented: "+directiveName);
    }
}
