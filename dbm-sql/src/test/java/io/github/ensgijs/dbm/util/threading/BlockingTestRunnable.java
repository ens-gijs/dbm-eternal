package io.github.ensgijs.dbm.util.threading;

/**
 * An implementation of {@link Runnable} which will initially block the running thread with
 * {@link Object#wait()} when {@link #handleRunStart()} is invoked.  The thread will remain blocked
 * until {@link #unblock()} is invoked.
 */
class BlockingTestRunnable extends TestRunnable {
    private volatile boolean unblocked = false;

    @Override
    public void handleRunStart() throws InterruptedException {
        synchronized (this) {
            while (!unblocked) {
                this.wait();
            }
        }
    }

    @Override
    public void handleRunFinish() {
    }

    /**
     * Check if the task has been unblocked yet.
     *
     * @return {@code true} if the thread has been unblocked.
     */
    public boolean isUnblocked() {
        return unblocked;
    }

    /**
     * Invoke to unblock any current or future executions for this {@link BlockingTestRunnable}.  Once invoked
     * no future blocking will occur.  In general this should be invoked at the end of every test
     * (fail or not) to avoid having left over blocked threads hanging around.
     */
    public void unblock() {
        synchronized (this) {
            unblocked = true;
            this.notifyAll();
        }
    }
}
