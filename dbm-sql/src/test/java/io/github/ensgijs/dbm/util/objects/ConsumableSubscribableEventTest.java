package io.github.ensgijs.dbm.util.objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ConsumableSubscribableEventTest {
    @Test
    public void testSanity() {
        ConsumableSubscribableEvent<Void> es = new ConsumableSubscribableEvent<>();
        AtomicInteger counter = new AtomicInteger();
        Consumer<Void> subscriber = nil -> counter.incrementAndGet();

        assertFalse(es.hasSubscribers());
        es.accept(null);
        assertFalse(es.unsubscribe(subscriber));

        assertSame(es, es.subscribe(subscriber));
        assertTrue(es.hasSubscribers());

        // shouldn't cause double call to subscribe a second time
        es.subscribe(subscriber);
        es.accept(null);
        assertEquals(1, counter.get());

        assertTrue(es.unsubscribe(subscriber));
        assertFalse(es.hasSubscribers());
        es.accept(null);
        assertEquals(1, counter.get());

        Consumer<Void> subscriber2 = nil -> counter.incrementAndGet();
        es.subscribe(subscriber);
        es.subscribe(subscriber2);
        es.accept(null);
        assertEquals(3, counter.get());
    }

    @Test
    public void testElevation() {
        Consumer<Void> event = null;
        AtomicInteger counter = new AtomicInteger();
        Consumer<Void> subscriber1 = nil -> counter.incrementAndGet();
        Consumer<Void> subscriber2 = nil -> counter.incrementAndGet();
        Consumer<Void> subscriber3 = nil -> counter.incrementAndGet();

        assertNull(ConsumableSubscribableEvent.subscribe(event, null));

        // should return given subscriber
        event = ConsumableSubscribableEvent.subscribe(event, subscriber1);
        assertSame(subscriber1, event);

        // should not elevate just because of a re-sub
        assertSame(subscriber1, ConsumableSubscribableEvent.subscribe(event, subscriber1));

        // should do nothing when passed a null subscriber
        assertSame(subscriber1, ConsumableSubscribableEvent.subscribe(event, null));
        assertSame(subscriber1, ConsumableSubscribableEvent.unsubscribe(event, null));

        // should elevate
        event = ConsumableSubscribableEvent.subscribe(event, subscriber2);
        assertInstanceOf(ConsumableSubscribableEvent.class, event);

        // should do nothing when passed a null subscriber
        assertSame(event, ConsumableSubscribableEvent.unsubscribe(event, null));

        // should notify both subscribers
        event.accept(null);
        assertEquals(2, counter.get());

        // already elevated, should return given event
        assertSame(event, ConsumableSubscribableEvent.subscribe(event, subscriber3));

        // should notify all 3 subscribers
        event.accept(null);
        assertEquals(5, counter.get());

        // unsubscribe one of three should return given event
        assertSame(event, ConsumableSubscribableEvent.unsubscribe(event, subscriber2));

        // should notify 2 remaining subscribers
        event.accept(null);
        assertEquals(7, counter.get());

        // should de-elevate to Consumer
        event = ConsumableSubscribableEvent.unsubscribe(event, subscriber1);
        assertSame(subscriber3, event);

        // should de-elevate to null
        event = ConsumableSubscribableEvent.unsubscribe(event, subscriber3);
        assertNull(event);

        // safe to unsub from null event
        assertNull(ConsumableSubscribableEvent.unsubscribe(null, subscriber3));

        event = new ConsumableSubscribableEvent<>();
        // safe to unsub from empty event (null subscribers set)
        assertNull(ConsumableSubscribableEvent.unsubscribe(event, subscriber3));

        // safe to unsub from empty event (non-null subscribers set)
        ((ConsumableSubscribableEvent<Void>) event).subscribe(subscriber1);
        ((ConsumableSubscribableEvent<Void>) event).unsubscribe(subscriber1);
        assertNull(ConsumableSubscribableEvent.unsubscribe(event, subscriber3));
    }
}

