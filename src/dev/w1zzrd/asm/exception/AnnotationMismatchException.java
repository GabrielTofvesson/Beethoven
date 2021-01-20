package dev.w1zzrd.asm.exception;

public class AnnotationMismatchException extends RuntimeException {
    public AnnotationMismatchException() {
    }

    public AnnotationMismatchException(String message) {
        super(message);
    }

    public AnnotationMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

    public AnnotationMismatchException(Throwable cause) {
        super(cause);
    }

    public AnnotationMismatchException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
