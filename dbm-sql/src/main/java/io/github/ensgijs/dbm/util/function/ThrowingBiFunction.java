package io.github.ensgijs.dbm.util.function;

@FunctionalInterface
public interface ThrowingBiFunction<T, U, R> {
    /**
     * Applies this function to the given argument.
     *
     * @param t the function argument
     * @return the function result
     */
    R apply(T t, U u) throws Throwable;
}
