package io.github.ensgijs.dbm.util.function;

@FunctionalInterface
public interface ExceptionalFunction<T, R, E extends Exception> extends ThrowingFunction<T, R>  {
    R apply(T t) throws E;
}
