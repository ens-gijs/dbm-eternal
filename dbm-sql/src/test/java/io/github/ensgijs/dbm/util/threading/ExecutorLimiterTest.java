package io.github.ensgijs.dbm.util.threading;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class ExecutorLimiterTest {
    protected static final int TEST_QTY = 10;
    protected static final int PARALLEL_COUNT = Math.min(5, TEST_QTY / 2);
    protected static final int THREAD_COUNT = PARALLEL_COUNT * 2;

    protected BlockingQueue<Runnable> pendingTasks;
    protected ThreadPoolExecutor threadPool;

    @BeforeEach
    public void setup() {
        pendingTasks = new LinkedBlockingQueue<>();
        threadPool = new ThreadPoolExecutor(PARALLEL_COUNT, THREAD_COUNT, 5, TimeUnit.SECONDS, pendingTasks);
    }

    @AfterEach
    public void cleanup() {
        threadPool.shutdownNow();
        threadPool = null;
        pendingTasks = null;
    }

    @Test
    public void getAndSetMaxConcurrencyTest() {
        ExecutorLimiter limiter = new ExecutorLimiter(threadPool, PARALLEL_COUNT);
        assertEquals(PARALLEL_COUNT, limiter.getMaxConcurrency());
        limiter.setMaxConcurrency(1);
        assertEquals(1, limiter.getMaxConcurrency());
    }


    @Test
    public void increaseMaxConcurrencyTest() {
        ExecutorLimiter limiter = new ExecutorLimiter(threadPool, 2);

        BlockingTestRunnable btr = new BlockingTestRunnable();

        try {
            limiter.execute(btr); // won't be able to run
            btr.blockTillStarted(1000);

            TestRunnable tr = new TestRunnable();
            limiter.execute(tr);
            limiter.setMaxConcurrency(2);
            tr.blockTillFinished(1000);  // should be able to complete now that limit was increased

            assertFalse(btr.isFinished());
        } finally {
            btr.unblock();
        }
//        var holding = limiter.shutdownNow();
        limiter.close();
    }

    @Test
    public void getUnsubmittedTaskCountTest() {
        ExecutorLimiter limiter = new ExecutorLimiter(threadPool, 1);

        assertEquals(0, limiter.getUnsubmittedTaskCount());

        BlockingTestRunnable btr = new BlockingTestRunnable();
        try {
            limiter.execute(btr);
            // block till started, and first check should still be zero
            btr.blockTillStarted(1000);

            for (int i = 0; i < TEST_QTY; i ++) {
                assertEquals(i, limiter.getUnsubmittedTaskCount());
                limiter.execute(() -> {});
            }
        } finally {
            btr.unblock();
        }
        limiter.close();
    }

    @Test
    public void consumeAvailableTest() {
        ExecutorLimiter limiter = new ExecutorLimiter(threadPool, PARALLEL_COUNT);
        List<TestRunnable> runnables = new ArrayList<>(PARALLEL_COUNT);
        for (int i = 0; i < PARALLEL_COUNT; i++) {
            TestRunnable tr = new TestRunnable();
            runnables.add(tr);
            limiter.waitingTasks.add(tr);
        }

        limiter.consumeAvailable();

        // should be fully consumed
        assertEquals(0, limiter.waitingTasks.size());

        Iterator<TestRunnable> it = runnables.iterator();
        while (it.hasNext()) {
            it.next().blockTillFinished(2000);  // throws exception if it does not finish
        }
        limiter.close();
    }

    @Test
    public void executeLimitTest() throws InterruptedException, TimeoutException {
        ExecutorLimiter limiter = new ExecutorLimiter(threadPool, PARALLEL_COUNT);
        final AtomicInteger running = new AtomicInteger(0);
        final AsyncVerifier verifier = new AsyncVerifier();
        List<TestRunnable> runnables = new ArrayList<>(TEST_QTY);
        for (int i = 0; i < TEST_QTY; i++) {
            TestRunnable tr = new TestRunnable(20) {
                @Override
                public void handleRunStart() {
                    int runningCount = running.incrementAndGet();
                    if (runningCount > PARALLEL_COUNT) {
                        verifier.fail(runningCount + " currently running");
                    }
                }

                @Override
                public void handleRunFinish() {
                    running.decrementAndGet();
                    verifier.signalComplete();
                }
            };
            limiter.execute(tr);
            runnables.add(tr);
        }

        verifier.waitForTest(1000 * 10, TEST_QTY);

        // verify execution
        Iterator<TestRunnable> it = runnables.iterator();
        while (it.hasNext()) {
            TestRunnable tr = it.next();
            tr.blockTillFinished(1000);

            assertEquals(1, tr.getRunCount());
        }
        limiter.close();
    }

}