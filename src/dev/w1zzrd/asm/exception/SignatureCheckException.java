package dev.w1zzrd.asm.exception;

public final class SignatureCheckException extends RuntimeException {
    public SignatureCheckException() {
    }

    public SignatureCheckException(String message) {
        super(message);
    }

    public SignatureCheckException(String message, Throwable cause) {
        super(message, cause);
    }

    public SignatureCheckException(Throwable cause) {
        super(cause);
    }

    public SignatureCheckException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
