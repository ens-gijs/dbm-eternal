package io.github.ensgijs.dbm.util.function;

/** maybe prefer {@link java.util.concurrent.Callable} */
@FunctionalInterface
public interface ExceptionalSupplier<T, E extends Exception> extends ThrowingSupplier<T> {
    T get() throws E;
}
