package io.github.ensgijs.dbm.util.function;

import java.util.Objects;

@FunctionalInterface
public interface ThrowingBiConsumer<T, U> {
    /**
     * Performs this operation on the given argument.
     *
     * @param t the first input argument
     * @param u the second input argument
     */
    void accept(T t, U u) throws Throwable;

    /**
     * Returns a composed {@code Consumer} that performs, in sequence, this
     * operation followed by the {@code after} operation. If performing either
     * operation throws an exception, it is relayed to the caller of the
     * composed operation.  If performing this operation throws an exception,
     * the {@code after} operation will not be performed.
     *
     * @param after the operation to perform after this operation
     * @return a composed {@code Consumer} that performs in sequence this
     * operation followed by the {@code after} operation
     * @throws NullPointerException if {@code after} is null
     */
    default ThrowingBiConsumer<T, U> andThen(ThrowingBiConsumer<? super T, ? super U> after) {
        Objects.requireNonNull(after);
        return (T t, U u) -> { accept(t, u); after.accept(t, u); };
    }
}