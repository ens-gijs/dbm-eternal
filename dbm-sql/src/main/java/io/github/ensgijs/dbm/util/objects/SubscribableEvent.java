package io.github.ensgijs.dbm.util.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Simple event subscribe notify API.
 * @param <T> notification value type.
 * @see ConsumableSubscribableEvent
 */
public interface SubscribableEvent<T> {

    /// @return True if there are any subscribers to notify the next time this event is triggered.
    boolean hasSubscribers();

    /**
     * Subscribe to this event.
     * @param subscriber Consumer to call when the event occurs.
     */
    SubscribableEvent<T> subscribe(@NotNull Consumer<T> subscriber);

    /**
     * Unsubscribe from this event.
     * @param subscriber Consumer that was being called when event occurs.
     * @return {@code true} if the subscriber was unsubscribed, {@code false} if the given subscriber was not subscribed.
     */
     boolean unsubscribe(@Nullable Consumer<T> subscriber);

    /**
     * Subscribe to, or unsubscribe from, this event.
     * @param subscriber Affected consumer.
     * @param subscribe Desired subscription action.
     */
    @Contract("null, true -> fail")
    void update(Consumer<T> subscriber, boolean subscribe);
}
