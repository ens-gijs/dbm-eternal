package io.github.ensgijs.dbm.util;

/// Simple helper to positively identify wrapped checked exceptions rethrown from within lambdas which
/// don't allow throwing checked exceptions.
public final class BubbleUpException extends RuntimeException {
    public BubbleUpException(Throwable cause) {
        super(cause);
    }

    /// If the given exception is a {@link BubbleUpException} its cause is returned, otherwise the given exception is returned.
    public static Throwable unwrap(Throwable ex) {
        if (ex instanceof BubbleUpException bx) {
            return bx.getCause();
        }
        return ex;
    }
}
