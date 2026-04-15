package io.github.ensgijs.dbm.util.objects;

import io.github.ensgijs.dbm.util.function.ExceptionalFunction;
import io.github.ensgijs.dbm.util.function.ExceptionalSupplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A container object which may or may not contain a non-null value or an exception.
 * <p>
 * This class is useful for functional programming patterns where checked exceptions
 * need to be carried through a pipeline or returned from a method without immediate
 * try-catch blocks.
 * </p>
 *
 * @param <V> the type of the value
 * @param <E> the type of the exception
 */
public class ValueOrException<V, E extends Exception> {

    /** A singleton instance representing an empty state. */
    private static final ValueOrException<?, ?> EMPTY = new ValueOrException<>(null, null);

    private final V value;
    private final E exception;

    /**
     * Internal constructor to initialize the result.
     * @param value the successful result (can be null)
     * @param exception the failure cause (can be null)
     */
    private ValueOrException(V value, E exception) {
        this.value = value;
        this.exception = exception;
    }

    /**
     * Creates a successful Result containing the provided value.
     * @param value the value to wrap
     * @return a ValueOrException containing the value
     */
    public static <V, E extends Exception> ValueOrException<V, E> forValue(V value) {
        return new ValueOrException<>(value, null);
    }

    /**
     * Creates a failed Result containing the provided exception.
     * @param exception the exception to wrap
     * @return a ValueOrException containing the exception
     */
    public static <V, E extends Exception> ValueOrException<V, E> forException(E exception) {
        return new ValueOrException<>(null, exception);
    }

    /**
     * Executes a supplier and captures any thrown exceptions into a ValueOrException.
     * @param func the supplier to execute
     * @return a ValueOrException containing the result or the caught exception
     */
    @SuppressWarnings("unchecked")
    public static <V, E extends Exception> ValueOrException<V, E> eval(ExceptionalSupplier<V, E> func) {
        try {
            return new ValueOrException<>(func.get(), null);
        } catch (Exception ex) {
            return new ValueOrException<>(null, (E) ex);
        }
    }

    /**
     * Executes a supplier and captures exceptions, with an optional callback for errors.
     * @param func the supplier to execute
     * @param notifyOnError a consumer that will be notified if an exception occurs
     * @return a ValueOrException containing the result or the caught exception
     */
    @SuppressWarnings("unchecked")
    public static <V, E extends Exception> ValueOrException<V, E> eval(
            ExceptionalSupplier<V, E> func,
            @Nullable Consumer<E> notifyOnError
    ) {
        try {
            return new ValueOrException<>(func.get(), null);
        } catch (Exception ex) {
            if (notifyOnError != null) notifyOnError.accept((E) ex);
            return new ValueOrException<>(null, (E) ex);
        }
    }

    /**
     * Wraps a function that throws a checked exception into a standard {@link Function}
     * that returns a {@code ValueOrException}. Useful for Stream APIs.
     * @param func the throwing function to wrap
     * @return a function that returns a ValueOrException instead of throwing
     */
    public static <V, T, E extends Exception> Function<T, ValueOrException<V, E>> wrap(ExceptionalFunction<T, V, E> func) {
        return (T t) -> ValueOrException.eval(() -> func.apply(t));
    }

    /**
     * Wraps a throwing function into a standard Function with error notification.
     * @param func the throwing function to wrap
     * @param notifyOnError callback for errors
     * @return a function that returns a ValueOrException
     */
    public static <V, T, E extends Exception> Function<T, ValueOrException<V, E>> wrap(
            ExceptionalFunction<T, V, E> func,
            @Nullable Consumer<E> notifyOnError
    ) {
        return (T t) -> ValueOrException.eval(() -> func.apply(t), notifyOnError);
    }

    /**
     * Returns the value if present, otherwise throws a {@link RetrievalException} whose
     * {@link RetrievalException#getCause() cause} is the original wrapped exception and whose
     * stack trace reflects the call site of this method.
     * <p>
     * When a specific checked exception type must be propagated, prefer
     * {@link #getOrThrow(Class)} over catching {@link RetrievalException} and calling
     * {@link RetrievalException#unwrap(Class)}.
     * </p>
     * @return the value if no exception is present
     * @throws RetrievalException if an exception is present, wrapping the original as cause
     */
    public V getOrThrow() {
        if (exception != null) {
            throw new RetrievalException(exception);
        }
        return value;
    }

    /**
     * Returns the value if present, otherwise constructs and throws a new instance of
     * {@code wrapperType} with the original exception as its cause.
     * <p>
     * This preserves both call stacks: the original exception's stack trace is available via
     * {@link Throwable#getCause()}, and the thrown wrapper carries a fresh stack trace from
     * this call site.
     * </p>
     * <p>
     * {@code wrapperType} must expose either a {@code (String, Throwable)} or a
     * {@code (Throwable)} constructor; if neither is found an {@link IllegalArgumentException}
     * is thrown immediately.
     * </p>
     *
     * @param wrapperType the exception type to throw when an exception is present
     * @param <T>         the exception type
     * @return the value if no exception is present
     * @throws T                     if an exception is present, wrapped in a new {@code wrapperType} instance
     * @throws IllegalArgumentException if {@code wrapperType} lacks a suitable constructor
     */
    @SuppressWarnings("unchecked")
    public <T extends Exception> V getOrThrow(@NotNull Class<T> wrapperType) throws T {
        if (exception == null) return value;
        try {
            T wrapper;
            try {
                wrapper = wrapperType.getConstructor(String.class, Throwable.class)
                        .newInstance("ValueOrException retrieval failed", exception);
            } catch (NoSuchMethodException ignored) {
                wrapper = wrapperType.getConstructor(Throwable.class).newInstance(exception);
            }
            throw wrapper;
        } catch (NoSuchMethodException nme) {
            throw new IllegalArgumentException(
                    wrapperType.getName() + " must have a (String, Throwable) or (Throwable) constructor"
                    + " to be used with getOrThrow(Class).", nme);
        } catch (ReflectiveOperationException roe) {
            // Constructor invocation failed (e.g. InvocationTargetException) — fall back to unchecked.
            throw new RetrievalException(exception);
        }
    }

    /**
     * Unchecked wrapper thrown by {@link #getOrThrow()} when an exception is present.
     * The wrapped exception is available via {@link #getCause()} and preserves its original
     * stack trace. This exception's own stack trace reflects the {@link #getOrThrow()} call site.
     */
    public static final class RetrievalException extends RuntimeException {
        RetrievalException(@NotNull Exception cause) {
            super("ValueOrException contained an exception", cause);
        }

        /**
         * Casts and returns the wrapped cause as the expected type.
         *
         * @param type the expected exception type
         * @param <X>  the expected exception type
         * @return the cause cast to {@code X}
         * @throws ClassCastException if the cause is not an instance of {@code type}
         */
        public <X extends Exception> X unwrap(@NotNull Class<X> type) {
            return type.cast(getCause());
        }
    }

    /**
     * @return true if an exception is present
     */
    public boolean hasException() {
        return exception != null;
    }

    /**
     * @return the raw value (may be null if an exception exists or if value was null)
     */
    public V getValue() {
        return value;
    }

    /// @return true if value is not null. May or may not have an exception.
    public boolean hasValue() {
        return value != null;
    }

    /**
     * @return the wrapped exception, or null if none
     */
    public E getException() {
        return exception;
    }

    /**
     * @return a type-safe empty instance
     */
    @SuppressWarnings("unchecked")
    public static <V, E extends Exception> ValueOrException<V, E> empty() {
        return (ValueOrException<V, E>) EMPTY;
    }

    /**
     * Returns a formatted string describing the value or the exception details
     * including stack trace.
     */
    @Override
    public String toString() {
        if (exception != null) {
            return "HAS ERROR : " + exception.getClass().getSimpleName() + " : " + exception.getMessage() + "\n" +
                    stackTraceToString(exception.getStackTrace());
        }
        return "VALUE: " + value;
    }

    private static String stackTraceToString(StackTraceElement[] trace) {
        StringBuilder s = new StringBuilder();
        boolean prefixNewLine = false;
        for (StackTraceElement traceElement : trace) {
            if (prefixNewLine) s.append('\n');
            else prefixNewLine = true;
            s.append("\tat ");
            toString(traceElement, s);
        }
        return s.toString();
    }

    private static void toString(@Nullable StackTraceElement traceElement, StringBuilder s) {
        // Based on impl from StackTraceElement::toString omitting class loader and module name
        if (traceElement != null) {
            s.append(traceElement.getClassName());
            s.append('.');
            s.append(traceElement.getMethodName());
            s.append('(');
            if (traceElement.isNativeMethod()) {
                s.append("Native Method)");
            } else if (traceElement.getFileName() != null && traceElement.getLineNumber() >= 0) {
                s.append(traceElement.getFileName());
                s.append(':');
                s.append(traceElement.getLineNumber());
                s.append(')');
            } else {
                s.append("Unknown Source)");
            }
        } else {
            s.append("null-trace");
        }
    }
}
