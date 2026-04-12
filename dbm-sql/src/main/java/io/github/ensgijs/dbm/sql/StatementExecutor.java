package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.util.function.ThrowingBiConsumer;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * A wrapper for {@link PreparedStatement} to simplify repeated manual execution.
 * Implements AutoCloseable to ensure the statement is closed after use.
 */
public record StatementExecutor(@NotNull PreparedStatement ps) implements AutoCloseable {

    /**
     * Helper to create a {@link StatementExecutor} from the provided {@link Connection}.
     * The caller is responsible for managing the connection lifecycle.
     * @throws DatabaseException if {@link Connection#prepareStatement} fails.
     * @see ExecutionContext#prepare(String)
     */
    public static StatementExecutor of(@NotNull Connection conn, @NotNull String sql) throws DatabaseException {
        try {
            return new StatementExecutor(conn.prepareStatement(sql));
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }
    
    /**
     * Maps parameters and executes an update.
     *
     * @param params Sql query params.
     * @return number of rows affected.
     * @throws DatabaseException if the execution fails.
     */
    public int executeUpdate(Object... params) throws DatabaseException {
        try {
            setParams(ps, params);
            return ps.executeUpdate();
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }

    /**
     * Maps parameters and executes a query.
     *
     * @param mapper function which will map a {@link ResultSet} to {@code T}
     * @param params Sql query params.
     * @param <T> mapping type.
     * @return resulting mapped object.
     * @throws DatabaseException if the execution fails.
     */
    public <T> T executeQuery(@NotNull ThrowingFunction<@NotNull ResultSet, T> mapper, Object... params) throws DatabaseException {
        setParams(ps, params);
        try (ResultSet rs = ps.executeQuery()) {
            return mapper.apply(rs);
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
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
     * <p><b>Warning:</b> it's strongly advised to use {@link #executeChunkedBatch(int, Collection, ThrowingBiConsumer)}
     * when performing more than about 5k updates at a time.</p>
     *
     * @param batchArgs  A collection where each element is an Object array of parameters.
     *                   Collection MUST NOT contain null elements.
     * @return an array of update counts containing one element for each command in the batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws DatabaseException if the execution fails.
     */
    public int[] executeBatch(@Nullable Collection<Object[]> batchArgs) throws DatabaseException {
        if (batchArgs == null || batchArgs.isEmpty()) return new int[0];
        Connection conn = null;
        boolean originalAutoCommit = true;
        boolean safe = false;
        try {
            conn = ps.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            safe = true;
            // Ensure auto-commit is off for batch performance
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
            }
            for (Object[] params : batchArgs) {
                setParams(ps, Objects.requireNonNull(params));
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            // Only commit chunks if the caller DID NOT start their own transaction.
            if (originalAutoCommit) {
                conn.commit();
            }
            return r;
        } catch (Throwable ex) {
            // If we managed the transaction, we should roll back on failure
            try {
                if (safe && originalAutoCommit) conn.rollback();
            } catch (SQLException ignored) {}

            throw DatabaseException.wrap(ex);
        } finally {
            // Restore the original state
            try {
                if (safe && originalAutoCommit)
                    conn.setAutoCommit(true);
            } catch (SQLException ignored) {}
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
     * <p><b>Warning:</b> it's strongly advised to use {@link #executeChunkedBatch(int, Collection, ThrowingBiConsumer)}
     * when performing more than about 5k updates at a time.</p>
     *
     * @param batchObjects The items to process.
     * @param mapper       The function to map each item to SQL parameters.
     * @return an array of update counts containing one element for each command in the batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws DatabaseException if the execution fails.
     */
    public <T> int[] executeBatch(
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<@NotNull T, @Nullable Object @NotNull[]> mapper
    ) throws DatabaseException {
        if (batchObjects == null || batchObjects.isEmpty())
            return new int[0];
        Connection conn = null;
        boolean originalAutoCommit = true;
        boolean safe = false;
        try {
            conn = ps.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            safe = true;
            // Ensure auto-commit is off for batch performance
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
            }
            final Object[] args = new Object[ps.getParameterMetaData().getParameterCount()];
            for (T obj : batchObjects) {
                Arrays.fill(args, null);
                mapper.accept(Objects.requireNonNull(obj), args);
                setParams(ps, args);
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            // Only commit chunks if the caller DID NOT start their own transaction.
            if (originalAutoCommit) {
                conn.commit();
            }
            return r;
        } catch (Throwable ex) {
            // If we managed the transaction, we should roll back on failure
            try {
                if (safe && originalAutoCommit)
                    conn.rollback();
            } catch (SQLException ignored) {}

            throw DatabaseException.wrap(ex);
        } finally {
            // Restore the original state
            try {
                if (safe && originalAutoCommit)
                    conn.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    /**
     * Calculates the largest data chunking size LE {@code maxChunkingSize} to most evenly divide {@code size} into chunks.
     * @param size total data size
     * @param maxChunkingSize maximum size of largest chunk
     * @return optimal chunking size LE maxChunkingSize
     */
    private static int calculateOptimalChunkingSize(final int size, final int maxChunkingSize) {
        if (size <= 0) return 0;
        if (maxChunkingSize < 1) throw new IllegalArgumentException();
        int numberOfChunks = (size + (maxChunkingSize - 1)) / maxChunkingSize;
        int chunkSize = size / numberOfChunks;
        if (size % numberOfChunks != 0) chunkSize++;
        return chunkSize;
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
     * @param batchArgs  A collection where each element is an Object array of parameters.
     *                   Collection MUST NOT contain null elements.
     * @return an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws ChunkedBatchExecutionException if any chunk fails.
     */
    public int[] executeChunkedBatch(
            int maxChunkingSize,
            @Nullable Collection<Object[]> batchArgs
    ) throws DatabaseException {
        if (batchArgs == null || batchArgs.isEmpty()) return new int[0];
        final int totalBatchSize = batchArgs.size();
        if (totalBatchSize <= maxChunkingSize)
            return executeBatch(batchArgs);
        final int chunkingSize = calculateOptimalChunkingSize(totalBatchSize, maxChunkingSize);

        final int totalSize = batchArgs.size();
        final int[] result = new int[totalSize];
        Connection conn = null;
        boolean originalAutoCommit = true;
        boolean safe = false;
        int i = 0;
        int pos = 0;
        int commitedCount = 0;
        try {
            conn = ps.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            safe = true;

            // Ensure auto-commit is off for batch performance and control
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
            }

            Iterator<Object[]> iter = batchArgs.iterator();

            while (iter.hasNext()) {
                for (i = 0; i < chunkingSize && iter.hasNext(); i++) {
                    setParams(ps, Objects.requireNonNull(iter.next()));
                    ps.addBatch();
                }
                int[] r = ps.executeBatch();

                // Only commit chunks if the caller DID NOT start their own transaction.
                if (originalAutoCommit) {
                    conn.commit();
                    commitedCount += r.length;
                }

                System.arraycopy(r, 0, result, pos, r.length);
                pos += r.length;
            }
            return result;
        } catch (Throwable ex) {
            // If we managed the transaction, we should roll back on failure
            try {
                if (safe && originalAutoCommit) conn.rollback();
            } catch (SQLException ignored) {}

            throw new ChunkedBatchExecutionException(commitedCount, totalSize, pos, chunkingSize, result, ex);
        } finally {
            // Restore the original state
            try {
                if (safe && originalAutoCommit)
                    conn.setAutoCommit(true);
            } catch (SQLException ignored) {}
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
     * @param batchObjects The items to process.
     * @param mapper       The function to map each item to SQL parameters.
     * @return an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws ChunkedBatchExecutionException if any chunk fails.
     */
    public <T> int[] executeChunkedBatch(
            int maxChunkingSize,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<@NotNull T, @NotNull Object[]> mapper
    ) throws ChunkedBatchExecutionException {
        if (batchObjects == null || batchObjects.isEmpty()) return new int[0];
        final int totalBatchSize = batchObjects.size();
        if (totalBatchSize <= maxChunkingSize)
            return executeBatch(batchObjects, mapper);
        final int chunkingSize = calculateOptimalChunkingSize(totalBatchSize, maxChunkingSize);

        final int totalSize = batchObjects.size();
        final int[] result = new int[totalSize];
        Connection conn = null;
        boolean originalAutoCommit = true;
        boolean safe = false;
        int i = 0;
        int pos = 0;
        int commitedCount = 0;
        try {
            conn = ps.getConnection();
            originalAutoCommit = conn.getAutoCommit();
            safe = true;

            // Ensure auto-commit is off for batch performance and control
            if (originalAutoCommit) {
                conn.setAutoCommit(false);
            }

            Iterator<T> iter = batchObjects.iterator();
            Object[] args = new Object[ps.getParameterMetaData().getParameterCount()];

            while (iter.hasNext()) {
                for (i = 0; i < chunkingSize && iter.hasNext(); i++) {
                    T obj = Objects.requireNonNull(iter.next());
                    Arrays.fill(args, null);
                    mapper.accept(obj, args);
                    setParams(ps, args);
                    ps.addBatch();
                }
                int[] r = ps.executeBatch();

                // Only commit chunks if the caller DID NOT start their own transaction.
                if (originalAutoCommit) {
                    conn.commit();
                    commitedCount += r.length;
                }

                System.arraycopy(r, 0, result, pos, r.length);
                pos += r.length;
            }
            return result;
        } catch (Throwable ex) {
            // If we managed the transaction, we should roll back on failure
            try {
                if (safe && originalAutoCommit) conn.rollback();
            } catch (SQLException ignored) {}

            throw new ChunkedBatchExecutionException(commitedCount, totalSize, pos, chunkingSize, result, ex);
        } finally {
            // Restore the original state
            try {
                if (safe && originalAutoCommit)
                    conn.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    public static void setParams(@NotNull PreparedStatement ps, @Nullable Object[] params) throws DatabaseException {
        if (params == null) return;
        try {
            for (int i = 0; i < params.length; i++) {
                if (!(params[i] instanceof UUID uuid)) {
                    ps.setObject(i + 1, params[i]);
                } else {
                    ps.setString(i + 1, uuid.toString());
                }
            }
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }

    @Override
    public void close() throws DatabaseException {
        try {
            ps.close();
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }
}

