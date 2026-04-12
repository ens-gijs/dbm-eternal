package io.github.ensgijs.dbm.util.function;

@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Throwable;
}
