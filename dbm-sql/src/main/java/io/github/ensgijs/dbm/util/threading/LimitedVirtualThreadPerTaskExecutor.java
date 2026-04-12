package io.github.ensgijs.dbm.util.threading;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Creates and wraps a {@link Executors#newVirtualThreadPerTaskExecutor()} to restrict the
 * concurrent number running virtual tasks.
 * <p>
 *
 * </p>
 */
public class LimitedVirtualThreadPerTaskExecutor extends ExecutorLimiter {
    /**
     * @param maxConcurrency maximum quantity of tasks to run in parallel
     */
    public LimitedVirtualThreadPerTaskExecutor(int maxConcurrency) {
        super(Executors.newVirtualThreadPerTaskExecutor(), maxConcurrency);
    }

    @Override
    public void close() {
        super.close();
        ((ExecutorService) executor).close();
    }
}
