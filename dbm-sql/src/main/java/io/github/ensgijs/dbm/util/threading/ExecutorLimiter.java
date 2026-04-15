package io.github.ensgijs.dbm.util.threading;

import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class is designed to limit how much parallel execution happens on a provided
 * {@link Executor}.  This allows the user to have one thread pool for all their code, and if they
 * want certain sections to have fewer levels of parallelism (possibly because those sections
 * would completely consume the global pool), they can wrap the executor in this class.
 * <p>
 * Thus providing you better control on the absolute thread count and how much parallelism can
 * occur in different sections of the program.
 * <p>
 * This is an alternative from having to create multiple thread pools.  By using this you also
 * are able to accomplish more efficiently thread use than multiple thread pools would.
 * <p>
 * Based somewhat on
 * <a href="https://github.com/threadly/threadly/blob/master/src/main/java/org/threadly/concurrent/wrapper/limiter/ExecutorLimiter.java">Threadly ExecutorLimiter source</a>
 * @see LimitedVirtualThreadPerTaskExecutor
 */
public class ExecutorLimiter extends AbstractExecutorService {
    private static final Logger logger = Logger.getLogger("ExecutorLimiter");
    private static final VarHandle STATE;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(ExecutorLimiter.class, "state", int.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    protected final Executor executor;
    protected final Deque<Runnable> waitingTasks;
    private final AtomicInteger currentlyRunning;
    private volatile int maxConcurrency;

    private final CountDownLatch terminationSignal = new CountDownLatch(1);
    // states: RUNNING -> SHUTDOWN -> TERMINATED
    private static final int RUNNING    = 0;
    private static final int SHUTDOWN   = 1;
    private static final int TERMINATED = 2;
    private volatile int state;

    /**
     * Construct a new execution limiter that implements the {@link Executor} interface.
     *
     * @param executor {@link Executor} to submit task executions to.
     * @param maxConcurrency maximum quantity of tasks to run in parallel
     */
    public ExecutorLimiter(@NotNull Executor executor, int maxConcurrency) {
        if (maxConcurrency <= 0)
            throw new IllegalArgumentException("invalid maxConcurrency");
        if (executor instanceof ThreadPoolExecutor.CallerRunsPolicy)
            throw new IllegalArgumentException("executor cannot be CallerRunsPolicy");
        this.executor = executor;
        this.waitingTasks = new ConcurrentLinkedDeque <>();
        this.currentlyRunning = new AtomicInteger(0);
        this.maxConcurrency = maxConcurrency;
        this.state = RUNNING;
    }

    @Override
    public void execute(@NotNull Runnable task) {
        ensureNotShutdown();
        waitingTasks.add(task);
        consumeAvailable();
    }

    /**
     * Call to check what the maximum concurrency this limiter will allow.
     *
     * @return maximum concurrent tasks to be run
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * Updates the concurrency limit for this limiter.  If reducing the limit, there will be no
     * attempt or impact on tasks already limiting.  Instead, new tasks just won't be submitted to the
     * parent pool until existing tasks complete and go below the new limit.
     *
     * @param maxConcurrency maximum quantity of tasks to run in parallel
     */
    public void setMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency <= 0) throw new IllegalArgumentException();

        boolean increasing = this.maxConcurrency < maxConcurrency;
        this.maxConcurrency = maxConcurrency;
        if (increasing) {
            consumeAvailable();
        }
    }

    /**
     * Query how many tasks are being withheld from the parent scheduler.  Returning the size of the
     * queued tasks waiting for submission to the pool.
     *
     * @return Quantity of tasks queued in this limiter
     */
    public int getUnsubmittedTaskCount() {
        return waitingTasks.size();
    }

    /// The count of task which are currently, actively, being executed.
    public int getCurrentlyRunning() {
        return currentlyRunning.get();
    }

    /// True if either {@link #getUnsubmittedTaskCount()} or {@link #getCurrentlyRunning()} would return non-zero.
    public boolean isBusy() {
        return getUnsubmittedTaskCount() > 0 || getCurrentlyRunning() > 0;
    }

    /**
     * Takes the next task in the queue iff we are currently under {@link #maxConcurrency}.
     *
     * @return a task that can be submitted to the wrapped executor or null if there are no pending tasks or if
     * we're already at max parallelism.
     */
    protected Runnable tryPoll() {
        while (!waitingTasks.isEmpty()) {  // loop till we have a result
            int currentValue = currentlyRunning.get();
            if (currentValue < maxConcurrency) {
                if (currentlyRunning.compareAndSet(currentValue, currentValue + 1)) {
                    var task = waitingTasks.poll();
                    if (task != null) {
                        return task;
                    } else {
                        currentlyRunning.decrementAndGet();
                    }
                }
            } else {
                break;
            }
        }
        return null;
    }

    /**
     * Submit any tasks that we can to the parent executor (dependent on our pools limit).
     */
    protected void consumeAvailable() {
        if (state < TERMINATED) {
            var task = tryPoll();
            while (task != null) {
                try {
                    executor.execute(new LimiterRunnableWrapper<>(task));
                } catch (RejectedExecutionException ex) {
                    logger.log(Level.WARNING, "Task execution rejected! Re-queueing the task " + task, ex);
                    currentlyRunning.decrementAndGet();
                    waitingTasks.addFirst(task);
                    return;
                }
                task = tryPoll();
            }
        }
    }

    protected void taskComplete() {
        currentlyRunning.decrementAndGet();
        // Risk: Recursion if executor is CallerRunsPolicy.
        consumeAvailable();
        if (state == SHUTDOWN) {
            tryTerminate();
        }
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new LimiterRunnableWrapper<>(runnable, value);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new LimiterRunnableWrapper<>(callable);
    }

    /**
     * Throws RejectedExecutionException if the executor has been shutdown.
     */
    private void ensureNotShutdown() {
        if (state >= SHUTDOWN) {
            // shutdown or terminated
            throw new RejectedExecutionException();
        }
    }
    /**
     * Attempts to shutdown and terminate the executor.
     */
    private void tryShutdownAndTerminate() {
        if (STATE.compareAndSet(this, RUNNING, SHUTDOWN))
            tryTerminate();
    }

    /**
     * Attempts to terminate if already shutdown. If this method terminates the
     * executor then it signals any threads that are waiting for termination.
     */
    private void tryTerminate() {
        assert state >= SHUTDOWN;
        if (waitingTasks.isEmpty() && STATE.compareAndSet(this, SHUTDOWN, TERMINATED)) {
            // signal waiters
            terminationSignal.countDown();
        }
    }

    @Override
    public void shutdown() {
        if (!isShutdown())
            tryShutdownAndTerminate();
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
        if (!isTerminated())
            tryShutdownAndTerminate();
        return new ArrayList<>(waitingTasks);
    }

    @Override
    public boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state >= TERMINATED;
    }

    /**
     * Blocks until no tasks are actively running or waiting to be submitted, or until the
     * timeout elapses. Does <em>not</em> shut down the executor.
     *
     * @param timeout maximum time to wait
     * @param unit    time unit of the timeout
     * @return {@code true} if the executor became idle within the timeout; {@code false} if the
     *         timeout elapsed while tasks were still in-flight
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitIdle(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        // Standard j.u.c. deadline pattern. System.nanoTime() is monotonically non-decreasing
        // within a JVM process, starting near zero (system-boot origin), so overflow is not
        // a practical concern for any realistic timeout value.
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (isBusy()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) return false;
            Thread.sleep(Math.min(50L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        if (isTerminated()) {
            return true;
        } else {
            return terminationSignal.await(timeout, unit);
        }
    }

    protected class LimiterRunnableWrapper<V> extends FutureTask<V> {

        public LimiterRunnableWrapper(Runnable task) {
            super(task, null);
        }

        public LimiterRunnableWrapper(Runnable task, V result) {
            super(task, result);
        }

        public LimiterRunnableWrapper(Callable<V> task) {
            super(task);
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                taskComplete();
            }
        }
    }
}