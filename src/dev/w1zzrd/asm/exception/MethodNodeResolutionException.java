package dev.w1zzrd.asm.exception;

public class MethodNodeResolutionException extends RuntimeException {
    public MethodNodeResolutionException() {
    }

    public MethodNodeResolutionException(String message) {
        super(message);
    }

    public MethodNodeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public MethodNodeResolutionException(Throwable cause) {
        super(cause);
    }

    public MethodNodeResolutionException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
