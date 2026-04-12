package io.github.ensgijs.dbm.util.objects;

import io.github.ensgijs.dbm.util.function.ExceptionalFunction;
import io.github.ensgijs.dbm.util.function.ExceptionalSupplier;
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
     * Returns the value if present, otherwise throws the contained exception.
     * <p>
     * Note: This method overwrites the exception stack trace with the current execution
     * point to make debugging easier from the point of retrieval.
     * </p>
     * @return the value if no exception exists
     * @throws E the wrapped exception
     */
    public V getOrThrow() throws E {
        if (exception != null) {
            // TODO: try to leverage exception.addSuppressed() here
            // Updating stack trace to show where getOrThrow was called
            exception.setStackTrace(new Exception().getStackTrace());
            throw exception;
        }
        return value;
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
