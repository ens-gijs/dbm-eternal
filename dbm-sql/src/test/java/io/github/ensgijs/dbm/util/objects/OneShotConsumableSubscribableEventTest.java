package io.github.ensgijs.dbm.util.objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OneShotConsumableSubscribableEventTest {

    // -------------------------------------------------------------------------
    // Basic behavioral tests
    // -------------------------------------------------------------------------

    @Test
    void hasSubscribers_falseWhenNoneAdded() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        assertFalse(event.hasSubscribers());
    }

    @Test
    void hasSubscribers_trueAfterSubscribe() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.subscribe(v -> {});
        assertTrue(event.hasSubscribers());
    }

    @Test
    void hasSubscribers_falseAfterUnsubscribe() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        Consumer<String> sub = v -> {};
        event.subscribe(sub);
        event.unsubscribe(sub);
        assertFalse(event.hasSubscribers());
    }

    @Test
    void hasFired_falseBeforeAccept() {
        assertFalse(new OneShotConsumableSubscribableEvent<String>().hasFired());
    }

    @Test
    void hasFired_trueAfterAccept() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept("x");
        assertTrue(event.hasFired());
    }

    @Test
    void accept_notifiesSubscriber() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        List<String> received = new ArrayList<>();
        event.subscribe(received::add);
        event.accept("hello");
        assertEquals(List.of("hello"), received);
    }

    @Test
    void accept_notifiesMultipleSubscribers() {
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        AtomicInteger sum = new AtomicInteger();
        event.subscribe(sum::addAndGet);
        event.subscribe(sum::addAndGet);
        event.accept(3);
        assertEquals(6, sum.get());
    }

    @Test
    void accept_nullValueIsDelivered() {
        var event = new OneShotConsumableSubscribableEvent<Void>();
        AtomicInteger counter = new AtomicInteger();
        event.subscribe(nil -> {
            counter.incrementAndGet();
            assertNull(nil);
        });
        event.accept(null);
        assertEquals(1, counter.get());
    }

    @Test
    void accept_secondCallThrowsIfDifferentValuePassed() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept("first");
        assertThrows(IllegalStateException.class, () -> event.accept("second"));
        assertDoesNotThrow(() -> event.accept("first"));
    }

    @Test
    void accept_secondCallThrowsIfDifferentValuePassed_whenNoSubscribers() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept("first");
        assertThrows(IllegalStateException.class, () -> event.accept("second"));
        assertDoesNotThrow(() -> event.accept("first"));
    }

    // -------------------------------------------------------------------------
    // Late-subscribe (post-fire) tests
    // -------------------------------------------------------------------------

    @Test
    void subscribe_afterFire_invokesImmediately() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept("late");
        List<String> received = new ArrayList<>();
        event.subscribe(received::add);
        assertEquals(List.of("late"), received);
    }

    @Test
    void subscribe_afterFire_withNullValue_invokesImmediately() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept(null);
        List<String> received = new ArrayList<>();
        event.subscribe(received::add);
        assertEquals(1, received.size());
        assertNull(received.getFirst());
    }

    @Test
    void subscribe_afterFire_doesNotRetainSubscriber() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept("x");
        AtomicInteger callCount = new AtomicInteger();
        event.subscribe(v -> callCount.incrementAndGet());
        // The event is already fired; no second accept is possible, but the subscriber
        // should not be retained in the set.
        assertFalse(event.hasSubscribers());
        assertEquals(1, callCount.get());
    }


    // -------------------------------------------------------------------------
    // Unsubscribe behaviour
    // -------------------------------------------------------------------------

    @Test
    void unsubscribe_returnsTrueWhenPresent() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        Consumer<String> sub = v -> {};
        event.subscribe(sub);
        assertTrue(event.unsubscribe(sub));
    }

    @Test
    void unsubscribe_returnsFalseWhenAbsent() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        assertFalse(event.unsubscribe(v -> {}));
    }

    @Test
    void unsubscribe_preventsNotification() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        AtomicInteger callCount = new AtomicInteger();
        Consumer<String> sub = v -> callCount.incrementAndGet();
        event.subscribe(sub);
        event.unsubscribe(sub);
        event.accept("x");
        assertEquals(0, callCount.get());
    }

    @Test
    void unsubscribe_nullSubscriber_returnsFalse() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        assertFalse(event.unsubscribe(null));
    }

    @Test
    void unsubscribe_afterFire_returnsFalse() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        Consumer<String> sub = v -> {};
        event.subscribe(sub);
        event.accept("x");
        // subscriber was removed during accept's drain loop
        assertFalse(event.unsubscribe(sub));
    }

    // -------------------------------------------------------------------------
    // update() delegation
    // -------------------------------------------------------------------------

    @Test
    void update_trueSubscribes() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        AtomicInteger callCount = new AtomicInteger();
        Consumer<String> sub = v -> callCount.incrementAndGet();
        event.update(sub, true);
        event.accept("x");
        assertEquals(1, callCount.get());
    }

    @Test
    void update_falseUnsubscribes() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        AtomicInteger callCount = new AtomicInteger();
        Consumer<String> sub = v -> callCount.incrementAndGet();
        event.subscribe(sub);
        event.update(sub, false);
        event.accept("x");
        assertEquals(0, callCount.get());
    }

    @Test
    void update_nullSubscriberWithSubscribeTrue_throws() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        assertThrows(NullPointerException.class, () -> event.update(null, true));
    }

    // -------------------------------------------------------------------------
    // subscribe() null guard
    // -------------------------------------------------------------------------

    @Test
    void subscribe_nullSubscriber_throws() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        assertThrows(NullPointerException.class, () -> event.subscribe(null));
    }

    // -------------------------------------------------------------------------
    // toString smoke tests
    // -------------------------------------------------------------------------

    @Test
    void toString_unfiredNoSubscribers() {
        var str = new OneShotConsumableSubscribableEvent<String>().toString();
        assertTrue(str.contains("unfired"));
        assertTrue(str.contains("no subscribers"));
    }

    @Test
    void toString_unfiredWithSubscribers() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.subscribe(v -> {});
        var str = event.toString();
        assertTrue(str.contains("unfired"));
        assertTrue(str.contains("1 subscriber"));
    }

    @Test
    void toString_afterFire() {
        var event = new OneShotConsumableSubscribableEvent<String>();
        event.accept("payload");
        assertTrue(event.toString().contains("fired"));
    }

    // -------------------------------------------------------------------------
    // Concurrency tests
    // -------------------------------------------------------------------------

    /**
     * Many threads subscribe concurrently before accept() is called.
     * Each subscriber must be notified exactly once.
     */
    @RepeatedTest(10)
    @Timeout(10)
    void concurrency_manySubscribersBeforeFire_eachNotifiedExactlyOnce() throws InterruptedException, BrokenBarrierException {
        final int threadCount = 50;
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        AtomicInteger totalCalls = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);
        try(ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        event.subscribe(v -> totalCalls.incrementAndGet());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            barrier.await(); // release all subscriber threads simultaneously
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        event.accept(42);

        assertEquals(threadCount, totalCalls.get(),
                "Every pre-fire subscriber should be notified exactly once");
    }

    /**
     * Many threads subscribe concurrently while accept() fires at the same time.
     * Each subscriber must receive the value exactly once regardless of timing.
     */
    @RepeatedTest(20)
    @Timeout(10)
    void concurrency_subscribeRacesWithAccept_exactlyOnceDelivery() throws InterruptedException {
        final int subscriberThreads = 40;
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        AtomicInteger totalCalls = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(subscriberThreads + 1); // +1 for accept thread
        try(ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < subscriberThreads; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        event.subscribe(v -> totalCalls.incrementAndGet());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                if (i == subscriberThreads / 2) {
                    // Accept thread waits at the same barrier so everything starts at once
                    pool.submit(() -> {
                        try {
                            barrier.await();
                            event.accept(1);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(subscriberThreads, totalCalls.get(),
                "Every subscriber must be called exactly once regardless of race with accept()");
    }

    /**
     * Concurrent calls to accept() must result in exactly one success and the rest must throw.
     */
    @RepeatedTest(10)
    @Timeout(10)
    void concurrency_multipleAcceptCalls_exactlyOneFires() throws InterruptedException {
        final int threadCount = 20;
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger exceptionCount = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try(ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final int val = i;
                pool.submit(() -> {
                    try {
                        barrier.await();
                        event.accept(val);
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        exceptionCount.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(1, successCount.get(), "Exactly one accept() call should succeed");
        assertEquals(threadCount - 1, exceptionCount.get(), "All other accept() calls should throw");
    }

    /**
     * Late subscribers (post-fire) arriving concurrently must each receive the value exactly once.
     */
    @RepeatedTest(10)
    @Timeout(10)
    void concurrency_lateSubscribersPostFire_eachInvokedExactlyOnce() throws InterruptedException {
        final int threadCount = 50;
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        event.accept(99);

        AtomicInteger totalCalls = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        try(ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        event.subscribe(v -> totalCalls.incrementAndGet());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(threadCount, totalCalls.get(),
                "Every late subscriber should be invoked exactly once");
    }

    /**
     * Validates no stale subscribers are retained in the set after the event fires,
     * even under concurrent subscribe + accept races.
     */
    @RepeatedTest(10)
    @Timeout(10)
    void concurrency_noSubscribersRetainedAfterFire() throws InterruptedException {
        final int threadCount = 30;
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);
        try(ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        event.subscribe(v -> {
                        });
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            pool.submit(() -> {
                try {
                    barrier.await();
                    event.accept(0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertFalse(event.hasSubscribers(),
                "Subscriber set should be empty after event fires");
    }

    /**
     * Delivered values are consistent — all subscribers see the same value.
     */
    @RepeatedTest(5)
    @Timeout(10)
    void concurrency_allSubscribersReceiveSameValue() throws InterruptedException {
        final int threadCount = 40;
        var event = new OneShotConsumableSubscribableEvent<Integer>();
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);
        try(ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                        event.subscribe(received::add);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            pool.submit(() -> {
                try {
                    barrier.await();
                    event.accept(7);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(threadCount, received.size());
        assertTrue(received.stream().allMatch(v -> v == 7),
                "All subscribers must receive the same fired value");
    }
}
