package io.github.ensgijs.dbm.sql;

public class ChunkedBatchExecutionException extends DatabaseException {
    private final int commitedCount;
    private final int totalRecordCount;
    private final int failedChunkStart;
    private final int chunkingSize;
    private final int[] updateCounts;

    public ChunkedBatchExecutionException(int commitedCount, int totalRecordCount, int failedChunkStart, int chunkingSize, int[] updateCounts, Throwable cause) {
        super(String.format("Chunked batch operation failed! Commited %d of %d records (lastChunkStart=%d, chunkingSize=%d).",
                commitedCount, totalRecordCount, failedChunkStart, chunkingSize), cause);
        this.commitedCount = commitedCount;
        this.totalRecordCount = totalRecordCount;
        this.failedChunkStart = failedChunkStart;
        this.chunkingSize = chunkingSize;
        this.updateCounts = updateCounts;
    }

    /// Number of records which were successfully commited to the table.
    public int commitedCount() {
        return commitedCount;
    }

    /// Total count of records prior to chunking.
    public int totalRecordCount() {
        return totalRecordCount;
    }

    /// Position of the first record in the chunk which caused the failure.<br/>
    /// If a specific record caused the query failure it will be between here and {@link #chunkingSize} elements beyond.
    public int failedChunkStart() {
        return failedChunkStart;
    }

    /// Actual size of each chunked batch.<br/>
    /// If a specific record caused the query failure it will be between {@link #failedChunkStart} and this many elements beyond.
    public int chunkingSize() {
        return chunkingSize;
    }

    /**
     * The result which would have been returned if we had logged and bailed instead of thrown this exception.
     * <p>All values from {@link #failedChunkStart()} and beyond will always be zero.</p>
     * <p>An array of update counts containing one element for each command in the batch. The elements of the array are
     * ordered according to the order in which commands were added to the batch. Only entries up to the position
     * indicated by {@link #failedChunkStart} will contain the actual results of execution, all counts past this point
     * will always be zero.</p>
     */
    public int[] updateCounts() {
        return updateCounts;
    }
}
