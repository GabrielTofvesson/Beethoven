package dev.w1zzrd.asm.exception;

public class StateAnalysisException extends RuntimeException {
    public StateAnalysisException() {
    }

    public StateAnalysisException(String message) {
        super(message);
    }

    public StateAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }

    public StateAnalysisException(Throwable cause) {
        super(cause);
    }

    public StateAnalysisException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
