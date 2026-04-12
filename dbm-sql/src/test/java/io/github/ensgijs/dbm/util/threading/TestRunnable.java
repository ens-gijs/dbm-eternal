package io.github.ensgijs.dbm.util.threading;

import io.github.ensgijs.dbm.util.threading.OneShotCondition;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.fail;

class TestRunnable implements Runnable {
    private static final AtomicInteger RUN_ID_GEN = new AtomicInteger();
    protected final int runId;
    private final OneShotCondition startedCondition = new OneShotCondition();
    private final OneShotCondition finishedCondition = new OneShotCondition();
    private final AtomicInteger runCount = new AtomicInteger();

    private final int runDelayInMillis;
    private volatile boolean started = false;
    private volatile boolean finished = false;

    public void handleRunStart() throws InterruptedException {
    }

    public void handleRunFinish() {
    }


    public TestRunnable() {
        this(0);
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    public int getRunCount() {
        return runCount.get();
    }

    /**
     * Constructs a new runnable for unit testing.
     * <p>
     * This constructor allows the parameter for the runnable to sleep after {@link #handleRunStart()}
     * was called and before {@link #handleRunFinish()} is called.
     *
     * @param runTimeInMillis time for runnable to sleep in milliseconds
     */
    public TestRunnable(int runTimeInMillis) {
        runId = RUN_ID_GEN.incrementAndGet();
        this.runDelayInMillis = runTimeInMillis;
    }

    @Override
    public final void run() {
        started = true;
        try {
            startedCondition.complete();
            handleRunStart();
        } catch (InterruptedException e) {
            // ignored, just reset status
            Thread.currentThread().interrupt();
        } finally {
            if (runDelayInMillis > 0) {
                try {
                    Thread.sleep(runDelayInMillis);
                } catch (InterruptedException e) {
                    // ignored, just reset status
                    Thread.currentThread().interrupt();
                }
            }
            runCount.incrementAndGet();
            try {
                handleRunFinish();
            } finally {
                finished = true;
                finishedCondition.complete();
            }
        }
    }

    /**
     * Blocks until run has been called at least once.
     *
     * @param timeoutMs time to wait for run to be called before throwing exception
     */
    public void blockTillStarted(int timeoutMs) {
        try {
            if (!startedCondition.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                fail("Thread did not start in the time allowed.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(ex.getMessage());
        }
    }

    /**
     * Blocks until run has completed at least once.
     *
     * @param timeoutMs time to wait for run to complete before throwing exception
     */
    public void blockTillFinished(int timeoutMs) {
        try {
            if (!finishedCondition.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                fail("Thread did not complete in the time allowed.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(ex.getMessage());
        }
    }
}
