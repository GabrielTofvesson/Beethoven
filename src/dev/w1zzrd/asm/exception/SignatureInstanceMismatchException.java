package dev.w1zzrd.asm.exception;

public class SignatureInstanceMismatchException extends RuntimeException {
    public SignatureInstanceMismatchException() {
    }

    public SignatureInstanceMismatchException(String message) {
        super(message);
    }

    public SignatureInstanceMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

    public SignatureInstanceMismatchException(Throwable cause) {
        super(cause);
    }

    public SignatureInstanceMismatchException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
