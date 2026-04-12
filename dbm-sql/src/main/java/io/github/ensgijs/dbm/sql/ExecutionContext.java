package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.util.function.ThrowingBiConsumer;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

/**
 * A helper context provided during a transaction or session to simplify
 * executing multiple statements on a single reused connection.
 * <p>NOTE: This object does not take ownership of the provided {@link Connection} or its lifecycle.</p>
 * @see SqlClient#executeTransaction(ThrowingFunction)
 * @see SqlClient#executeSession(ThrowingFunction) 
 */
public record ExecutionContext(/*@NotNull*/ Connection connection, /*@NotNull*/ SqlDialect activeDialect) {
    // NOTE: mockito fails to mock this class with @NotNull annotations on members!

    public void commit() throws SQLException {
        connection.commit();
    }

    public void rollback() throws SQLException {
        connection.rollback();
    }
    
    /**
     * Prepares a statement for manual reuse within this transaction.
     *
     * @param sql The SQL string.
     * @return A handler that simplifies execution and parameter mapping.
     */
    public StatementExecutor prepare(@NotNull String sql) throws DatabaseException {
        return StatementExecutor.of(connection, sql);
    }

    /**
     * Executes a DML statement using the transaction's current connection.
     *
     * @param sql    The SQL string with '?' placeholders.
     * @param params Parameters to fill the placeholders.
     * @return The number of affected rows.
     * @throws DatabaseException if a database access error occurs.
     */
    public int executeUpdate(@NotNull String sql, Object... params) throws DatabaseException {
        try (StatementExecutor exec = prepare(sql)) {
            return exec.executeUpdate(params);
        }
    }

    /**
     * Executes a query within the transaction.
     * Note: The ResultSet must be consumed before the next statement
     * is executed on this context to avoid driver-specific conflicts.
     * @throws DatabaseException if a database access error occurs.
     */
    public <T> T executeQuery(
            @NotNull String sql,
            @NotNull ThrowingFunction<@NotNull ResultSet, T> mapper,
            Object... params) throws DatabaseException {
        try (StatementExecutor exec = prepare(sql)) {
            return exec.executeQuery(mapper, params);
        }
    }

    /**
     * Executes a single batch of updates.<br/>
     * <b>Intent-Based Behavior:</b>
     * <ul>
     * <li><b>Session Mode:</b> If {@code autoCommit} is enabled on entry, this method
     * assumes "Session Mode" and will execute the batch within a transaction and call {@code commit()}
     * before returning the connection to autoCommit.</li>
     *
     * <li><b>Transaction Mode:</b> If {@code autoCommit} is disabled on entry, this method
     * assumes it is part of a larger atomic operation and will NOT call {@code commit()},
     * preserving the caller's transaction boundaries.</li>
     * </ul>
     * <p><b>Warning:</b> it's strongly advised to use {@link #executeChunkedBatch(int, String, Collection, ThrowingBiConsumer)}
     * when performing more than about 5k updates at a time.</p>
     *
     * @param sql The SQL string.
     * @param batchArgs  A collection where each element is an Object array of parameters.
     *                   Collection MUST NOT contain null elements.
     * @return an array of update counts containing one element for each command in the batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws DatabaseException if the execution fails.
     */
    public int[] executeBatch(@NotNull String sql, @Nullable Collection<Object[]> batchArgs) throws DatabaseException {
        try (StatementExecutor exec = prepare(sql)) {
            return exec.executeBatch(batchArgs);
        }
    }

    /**
     * Executes a single batch of updates.<br/>
     * <b>Intent-Based Behavior:</b>
     * <ul>
     * <li><b>Session Mode:</b> If {@code autoCommit} is enabled on entry, this method
     * assumes "Session Mode" and will execute the batch within a transaction and call {@code commit()}
     * before returning the connection to autoCommit.</li>
     *
     * <li><b>Transaction Mode:</b> If {@code autoCommit} is disabled on entry, this method
     * assumes it is part of a larger atomic operation and will NOT call {@code commit()},
     * preserving the caller's transaction boundaries.</li>
     * </ul>
     * <p><b>Warning:</b> it's strongly advised to use {@link #executeChunkedBatch(int, String, Collection, ThrowingBiConsumer)}
     * when performing more than about 5k updates at a time.</p>
     *
     * @param sql The SQL string.
     * @param batchObjects The items to process.
     * @param mapper       The function to map each item to SQL parameters.
     * @return an array of update counts containing one element for each command in the batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws DatabaseException if the execution fails.
     */
    public <T> int[] executeBatch(
            @NotNull String sql,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<T, @NotNull Object[]> mapper
    ) throws DatabaseException {
        try (StatementExecutor exec = prepare(sql)) {
            return exec.executeBatch(batchObjects, mapper);
        }
    }

    /**
     * Splits updates in multiple batches to balance performance and database resource usage.<br/>
     * <b>Intent-Based Behavior:</b>
     * <ul>
     * <li><b>Session Mode:</b> If {@code autoCommit} is enabled on entry, this method
     * assumes "Session Mode" and will execute each chunk within a transaction and call {@code commit()}
     * after every chunk. This provides the benefits of executing a batch within a transaction while also preventing
     * the database undo/redo logs from growing indefinitely.<br/>
     * However, if a chunk fails, that one chunk will be rolled back, all prior chunks will have been commited to the
     * database, and no further chunks will be processed.</li>
     *
     * <li><b>Transaction Mode:</b> If {@code autoCommit} is disabled on entry, this method
     * assumes it is part of a larger atomic operation and will NOT call {@code commit()},
     * preserving the caller's transaction boundaries.</li>
     * </ul>
     * <p><b>Warning:</b> chunking very large batches in "Transaction Mode" may risk
     * database undo/redo logs growing to the point they start causing serious issues. Use "Session Mode" when in
     * doubt and handle any thrown {@link ChunkedBatchExecutionException}, taking advantage of the details it provides
     * about processing progress, to recover from a partially commited batch.</p>
     *
     * @param maxChunkingSize Max items per batch (500-1000 recommended, up to 5000 is generally OK).
     * @param sql The SQL string.
     * @param batchArgs  A collection where each element is an Object array of parameters.
     *                   Collection MUST NOT contain null elements.
     * @return an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws ChunkedBatchExecutionException if any chunk fails.
     */
    public <T> int[] executeChunkedBatch(
            int maxChunkingSize,
            @NotNull String sql,
            @Nullable Collection<Object[]> batchArgs
    ) throws ChunkedBatchExecutionException {
        try (StatementExecutor exec = prepare(sql)) {
            return exec.executeChunkedBatch(maxChunkingSize, batchArgs);
        }
    }

    /**
     * Splits updates in multiple batches to balance performance and database resource usage.<br/>
     * <b>Intent-Based Behavior:</b>
     * <ul>
     * <li><b>Session Mode:</b> If {@code autoCommit} is enabled on entry, this method
     * assumes "Session Mode" and will execute each chunk within a transaction and call {@code commit()}
     * after every chunk. This provides the benefits of executing a batch within a transaction while also preventing
     * the database undo/redo logs from growing indefinitely.<br/>
     * However, if a chunk fails, that one chunk will be rolled back, all prior chunks will have been commited to the
     * database, and no further chunks will be processed.</li>
     *
     * <li><b>Transaction Mode:</b> If {@code autoCommit} is disabled on entry, this method
     * assumes it is part of a larger atomic operation and will NOT call {@code commit()},
     * preserving the caller's transaction boundaries.</li>
     * </ul>
     * <p><b>Warning:</b> chunking very large batches in "Transaction Mode" may risk
     * database undo/redo logs growing to the point they start causing serious issues. Use "Session Mode" when in
     * doubt and handle any thrown {@link ChunkedBatchExecutionException}, taking advantage of the details it provides
     * about processing progress, to recover from a partially commited batch.</p>
     *
     * @param maxChunkingSize Max items per batch (500-1000 recommended, up to 5000 is generally OK).
     * @param sql The SQL string.
     * @param batchObjects The items to process.
     * @param mapper       The function to map each item to SQL parameters.
     * @return an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws ChunkedBatchExecutionException if any chunk fails.
     */
    public <T> int[] executeChunkedBatch(
            int maxChunkingSize,
            @NotNull String sql,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<T, @NotNull Object[]> mapper
    ) throws ChunkedBatchExecutionException {
        try (StatementExecutor exec = prepare(sql)) {
            return exec.executeChunkedBatch(maxChunkingSize, batchObjects, mapper);
        }
    }
}