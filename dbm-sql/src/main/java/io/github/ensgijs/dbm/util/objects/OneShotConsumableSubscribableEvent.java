package io.github.ensgijs.dbm.util.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread safe one-shot {@link SubscribableEvent} implementation.
 *
 * <p>{@link #accept(Object)} may only be called once. After it is called, the fired value is
 * captured and any subsequent calls to {@link #subscribe(Consumer)} will immediately invoke the
 * subscriber with the captured value rather than queuing it for a future notification.</p>
 *
 * <p>Subscribers registered before {@link #accept(Object)} is called are notified exactly once
 * when it fires. Subscribers registered after firing are invoked inline during
 * {@link #subscribe(Consumer)}.</p>
 *
 * <h2>Example Usage</h2>
 * <em>Notice the restricted return type of {@code onLoaded()}</em>
 * <pre>{@code
 *     class ResourceLoader {
 *         private final OneShotSubscribableEvent<Resource> onLoaded = new OneShotSubscribableEvent<>();
 *
 *         public SubscribableEvent<Resource> onLoaded() { return onLoaded; }
 *
 *         public void load() {
 *             Resource r = // ...
 *             onLoaded.accept(r); // notify all current subscribers and capture value
 *         }
 *     }
 *
 *     // Always receives the value, regardless of timing:
 *     loader.onLoaded().subscribe(r -> System.out.println("Loaded: " + r));
 * }</pre>
 *
 * @param <T> notification value type.
 * @see ConsumableSubscribableEvent
 */
public class OneShotConsumableSubscribableEvent<T> implements SubscribableEvent<T>, Consumer<T> {

    /**
     * Sentinel used to distinguish "not yet fired" from a legitimately null fired value.
     * Stored in the AtomicReference before accept() is called.
     */
    private static final Object UNFIRED = new Object();

    /**
     * Holds {@link #UNFIRED} until {@link #accept(Object)} is called, after which it holds
     * the captured value (which may itself be {@code null}).
     */
    private final AtomicReference<Object> firedValue = new AtomicReference<>(UNFIRED);

    private final Set<Consumer<T>> subscribers = ConcurrentHashMap.newKeySet(2);

    public OneShotConsumableSubscribableEvent() {}

    @Override
    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    /**
     * Subscribe to this event.
     *
     * <p>If {@link #accept(Object)} has already been called, {@code subscriber} is invoked
     * immediately with the captured value and is <em>not</em> retained for future calls
     * (there will be none).</p>
     *
     * @param subscriber Consumer to call when the event occurs.
     */
    @Override
    @SuppressWarnings("unchecked")
    public OneShotConsumableSubscribableEvent<T> subscribe(@NotNull Consumer<T> subscriber) {
        Object snapshot = firedValue.get();
        if (snapshot != UNFIRED) {
            subscriber.accept((T) firedValue.get());
            return this;
        }

        // Register the subscriber first, then re-check whether accept() raced ahead of us.
        // If it did, we must fire ourselves so this subscriber is not silently dropped.
        subscribers.add(subscriber);

        snapshot = firedValue.get();
        if (snapshot != UNFIRED) {
            // accept() already finished its iteration (or is finishing now).
            // Remove and fire inline to ensure exactly-once delivery even under a race.
            if (subscribers.remove(subscriber)) {
                subscriber.accept((T) snapshot);
            }
            // If remove() returned false, accept()'s loop already called this subscriber.
        }

        return this;
    }

    /**
     * Unsubscribe from this event.
     *
     * <p>Has no effect if the event has already fired.</p>
     *
     * @param subscriber Consumer to unsubscribe.
     * @return {@code true} if the subscriber was removed; {@code false} if it was not subscribed.
     */
    @Override
    public boolean unsubscribe(@Nullable Consumer<T> subscriber) {
        return subscriber != null && subscribers.remove(subscriber);
    }

    /**
     * Subscribe to, or unsubscribe from, this event.
     *
     * @param subscriber Affected consumer.
     * @param subscribe  {@code true} to subscribe, {@code false} to unsubscribe.
     * @throws NullPointerException if {@code subscriber} is {@code null} and {@code subscribe} is {@code true}.
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
     * Fire this event, notifying all current subscribers with {@code value} and capturing it for
     * late subscribers.
     *
     * @param value The event value (may be {@code null}).
     * @throws IllegalStateException if this event has already been fired with a different value.
     */
    @Override
    public void accept(T value) {
        // null stored directly represents a fired-with-null state
        if (!firedValue.compareAndSet(UNFIRED, value)) {
            if (firedValue.get() != value)
                throw new IllegalStateException("OneShotSubscribableEvent has already been fired");
            else
                return;
        }

        // Drain and notify all pending subscribers.
        // New subscribers arriving after this point will see firedValue != UNFIRED
        // and invoke themselves inline in subscribe().
        for (Consumer<T> s : subscribers) {
            // Remove first; if subscribe() races in with the same consumer and also
            // removes it, exactly one of the two will call accept — whichever wins remove().
            if (subscribers.remove(s)) {
                s.accept(value);
            }
        }
    }

    /** @return {@code true} if {@link #accept(Object)} has been called. */
    public boolean hasFired() {
        return firedValue.get() != UNFIRED;
    }

    @Override
    public String toString() {
        Object snapshot = firedValue.get();
        StringBuilder str = new StringBuilder(super.toString());
        if (snapshot == UNFIRED) {
            if (subscribers.isEmpty()) {
                str.append(" [unfired, no subscribers]");
            } else {
                str.append(" [unfired, ").append(subscribers.size()).append(" subscriber(s)]");
                for (var c : subscribers) {
                    str.append("\n  -> ").append(c);
                }
            }
        } else {
            str.append(" [fired: ").append(snapshot).append("]");
        }
        return str.toString();
    }
}
