package io.github.ensgijs.dbm.util.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread safe {@link Consumer} {@link SubscribableEvent} implementation.
 * <p>Implements {@link Consumer} allowing {@code tee} like behavior (pass one consumer as a callback,
 * then relay that result to many consumers).</p>
 * <p>There are two primary usage patters. Direct use and elevation.
 * <ul>
 * <li><b>Direct use:</b> define a class member of type {@link ConsumableSubscribableEvent} and provide a getter
 *  returning {@link SubscribableEvent} for it.
 * <li><b>Elevation:</b> define a {@link Consumer} member and expose subscribe/unsubscribe methods taking
 *  {@link Consumer} and use {@link #subscribe(Consumer, Consumer)} and {@link #unsubscribe(Consumer, Consumer)}
 *  to handle wrapping and unwrapping of the {@link Consumer} member.
 * </ul>
 * @param <T> notification value type.
 * @apiNote Warning: the caller must synchronize across subscribe/unsubscribe/update calls when using the static
 * elevation API, since the returned Consumer must be written back to the field atomically.
 * @see OneShotConsumableSubscribableEvent
 */
public class ConsumableSubscribableEvent<T> implements SubscribableEvent<T>, Consumer<T> {
    private final Set<Consumer<T>> subscribers = ConcurrentHashMap.newKeySet(2);

    public ConsumableSubscribableEvent() {}

    public ConsumableSubscribableEvent(Consumer<T> consumer) {
        subscribe(consumer);
    }

    @Override
    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    /**
     * Thread safe. Subscribe to this event.
     * @param subscriber Consumer to call when the event occurs.
     */
    @Override
    public ConsumableSubscribableEvent<T> subscribe(@NotNull Consumer<T> subscriber) {
        subscribers.add(subscriber);
        return this;
    }

    /**
     * Thread safe. Unsubscribe from this event.
     * @param subscriber Consumer that was being called when event occurs.
     * @return {@code true} if the subscriber was unsubscribed, {@code false} if the given subscriber was not subscribed.
     */
    @Override
    public boolean unsubscribe(@Nullable Consumer<T> subscriber) {
        return subscriber != null && subscribers.remove(subscriber);
    }

    /**
     * Thread safe. Subscribe to, or unsubscribe from, this event.
     * @param subscriber Affected consumer.
     * @param subscribe Desired subscription action.
     */
    @Override
    @Contract("null, true -> fail")
    public void update(Consumer<T> subscriber, boolean subscribe) {
        if (subscribe) {
            subscribe(Objects.requireNonNull(subscriber));
        } else {
            unsubscribe(subscriber);
        }
    }

    /**
     * Notifies all currently subscribed consumers. Weakly consistent —
     * subscribers added or removed during notification may or may not be called.
     */
    @Override
    public void accept(T value) {
        for (var s : subscribers) {
            s.accept(value);
        }
    }

    /**
     * Simplifies elevation logic for users.
     *
     * <h4>Example Usage</h4>
     * <pre>{@code
     *     class MyClass {
     *         Consumer callback;
     *         // ...
     *         public synchronized void subscribe(Consumer consumer) {
     *             callback = EventSubscribe.subscribe(callback, consumer);
     *         }
     *     }
     * }</pre>
     * @apiNote Warning: the caller must synchronize across subscribe/unsubscribe/update calls when using the static
     * elevation API, since the returned Consumer must be written back to the field atomically.
     */
    public static <T> Consumer<T> subscribe(@Nullable Consumer<T> currentConsumer, @Nullable Consumer<T> newConsumer) {
        if (currentConsumer == null)
            return newConsumer;
        if (newConsumer == null || currentConsumer == newConsumer)
            return currentConsumer;
        if (currentConsumer instanceof ConsumableSubscribableEvent<T> es) {
            return es.subscribe(newConsumer);
        }
        return new ConsumableSubscribableEvent<>(currentConsumer).subscribe(newConsumer);
    }

    /**
     * Performs inverse of {@link #subscribe(Consumer, Consumer)} while unsubscribing a consumer.
     *
     * <h4>Example Usage</h4>
     * <pre>{@code
     *     class MyClass {
     *         Consumer callback;
     *         // ...
     *         public synchronized void unsubscribe(Consumer consumer) {
     *             callback = EventSubscribe.unsubscribe(callback, consumer);
     *         }
     *     }
     * }</pre>
     * @apiNote Warning: the caller must synchronize across subscribe/unsubscribe/update calls when using the static
     * elevation API, since the returned Consumer must be written back to the field atomically.
     */
    public static <T> Consumer<T> unsubscribe(@Nullable Consumer<T> currentConsumer, @Nullable Consumer<T> subscriber) {
        if (currentConsumer == null)
            return null;
        if (subscriber == null)
            return currentConsumer;
        if (currentConsumer instanceof ConsumableSubscribableEvent<T> es) {
            es.unsubscribe(subscriber);
            if (es.subscribers.isEmpty()) {
                return null;
            } else if (es.subscribers.size() == 1) {
                return es.subscribers.iterator().next();
            }
            return es;
        }
        return null;
    }

    /**
     * Elevation helper to subscribe to, or unsubscribe from, an event.
     *
     * <h4>Example Usage</h4>
     * <pre>{@code
     *     class MyClass {
     *         Consumer callback;
     *         // ...
     *         public synchronized void updateSubscription(Consumer consumer, boolean subscribe) {
     *             callback = EventSubscribe.update(callback, consumer, subscribe);
     *         }
     *     }
     * }</pre>
     * @apiNote Warning: the caller must synchronize across subscribe/unsubscribe/update calls when using the static
     * elevation API, since the returned Consumer must be written back to the field atomically.
     */
    public static <T> Consumer<T> update(Consumer<T> currentConsumer, Consumer<T> subscriber, boolean subscribe) {
        if (subscribe) {
            return ConsumableSubscribableEvent.subscribe(currentConsumer, subscriber);
        } else {
            return ConsumableSubscribableEvent.unsubscribe(currentConsumer, subscriber);
        }
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder(super.toString());
        if (subscribers.isEmpty()) {
            str.append(" EMPTY");
        } else {
            for (var c : subscribers) {
                str.append("\n  -> ").append(c);
            }
        }
        return str.toString();
    }
}
