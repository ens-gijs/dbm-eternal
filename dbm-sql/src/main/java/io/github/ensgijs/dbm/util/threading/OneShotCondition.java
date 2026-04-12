package io.github.ensgijs.dbm.util.threading;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Allows for blocking with one of the {@link #await()} methods until someone calls {@link #complete()} at least once.
 * <p>
 *     Once {@link #complete()} has been called all future calls to {@link #await()} return immediately.
 * </p>
 */
public class OneShotCondition {
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private volatile boolean conditionMet = false;

    /** Non-blocking call to check if this condition has been completed. */
    public boolean isComplete() {
        return conditionMet;
    }

    /**
     * Marks this condition as complete. All callers blocked on {@link #await()} are notified.
     * <p>This method can be called more than once.</p>
     */
    public void complete() {
        if (conditionMet) return;

        lock.lock();
        try {
            conditionMet = true;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks indefinitely waiting for the condition to be completed.
     *
     * @throws InterruptedException if the current thread is interrupted
     *      (and interruption of thread suspension is supported).
     */
    public void await() throws InterruptedException {
        if (conditionMet) return;
        lock.lock();
        try {
            condition.await();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks for the specified time waiting for the condition to be completed.
     *
     * @param time the maximum time to wait
     * @param unit the time unit of the {@code time} argument
     * @return {@code false} if the waiting time detectably elapsed
     *         before return from the method, else {@code true}
     * @throws InterruptedException if the current thread is interrupted
     *         (and interruption of thread suspension is supported)
     */
    public boolean await(long time, TimeUnit unit) throws InterruptedException {
        if (conditionMet) return true;
        lock.lock();
        try {
            return condition.await(time, unit);
        } finally {
            lock.unlock();
        }
    }
}
