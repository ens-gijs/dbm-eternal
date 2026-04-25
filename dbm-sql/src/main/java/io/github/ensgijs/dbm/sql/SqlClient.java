package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.ensgijs.dbm.util.function.ThrowingBiConsumer;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import io.github.ensgijs.dbm.util.objects.ConsumableSubscribableEvent;
import io.github.ensgijs.dbm.util.objects.SubscribableEvent;
import io.github.ensgijs.dbm.util.threading.LimitedVirtualThreadPerTaskExecutor;
import io.github.ensgijs.dbm.platform.PlatformHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.sql.*;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SQL database connections using a HikariCP connection pool.
 * <p>
 * This manager handles the lifecycle of connections for both MySQL and SQLite,
 * providing utility methods to execute updates, run queries, and verify schema integrity.
 * </p>
 */
public class SqlClient {
    private final static Logger logger = Logger.getLogger("SqlClient");
    protected final @NotNull PlatformHandle platformHandle;
    private final ConsumableSubscribableEvent<SqlClient> onBeforePoolResetEvent = new ConsumableSubscribableEvent<>();
    private final ConsumableSubscribableEvent<SqlClient> onAfterPoolResetEvent = new ConsumableSubscribableEvent<>();
    private final ConsumableSubscribableEvent<SqlDialect> onBeforeDialectChangeEvent = new ConsumableSubscribableEvent<>();
    protected SqlConnectionConfig sqlConnectionConfig;
    protected @NotNull SqlDialect activeDialect = SqlDialect.UNDEFINED;
    protected HikariDataSource dataSource;
    protected final Function<@NotNull HikariConfig, HikariDataSource> hikariCreator;
    protected final LimitedVirtualThreadPerTaskExecutor asyncExecutor = new LimitedVirtualThreadPerTaskExecutor(1);
    private volatile CompletableFuture<Boolean> pendingPoolReset;

    /**
     * Initializes the manager and attempts to load the database configuration
     * from the plugin's default config file.
     * @param platformHandle The owner of this database manager.
     * @param sqlConnectionConfig {@link SqlConnectionConfig}
     */
    public SqlClient(@NotNull PlatformHandle platformHandle, @NotNull SqlConnectionConfig sqlConnectionConfig) {
        this.platformHandle = platformHandle;
        this.hikariCreator = HikariDataSource::new;
        setSqlConnectionConfig(sqlConnectionConfig);
    }

    @VisibleForTesting
    public SqlClient(
            @NotNull PlatformHandle platformHandle,
            @NotNull SqlConnectionConfig sqlConnectionConfig,
            @NotNull Function<@NotNull HikariConfig, HikariDataSource> hikariCreator
    ) {
        this.platformHandle = platformHandle;
        this.hikariCreator = hikariCreator;
        setSqlConnectionConfig(sqlConnectionConfig);
    }

    @Override
    public String toString() {
        return "SqlClient{" +
                "owner=" + platformHandle.name() +
                ", dialect=" + activeDialect +
                ", conn=" + sqlConnectionConfig.connectionId() +
                '}';
    }

    public @NotNull SqlDialect activeDialect() {
        return activeDialect;
    }

    /// Gets the {@link PlatformHandle} that owns this manager and the database.
    public @NotNull PlatformHandle getPlatformHandle() {
        return platformHandle;
    }

    /**
     * Updates the connection configuration, synchronously reconnecting the pool if the new config
     * differs from the current one (as determined by {@link SqlConnectionConfig#isEquivalent}).
     * <p>
     * This overload does not drain in-flight async tasks before resetting the pool.
     * Use {@link #setSqlConnectionConfig(SqlConnectionConfig, long, TimeUnit)} when live
     * reconnection is needed and in-flight work must complete cleanly.
     * </p>
     *
     * @param config The new connection configuration.
     * @return {@code true} if the configuration changed and a reconnection was performed;
     *         {@code false} if the config was equivalent to the current one and no action was taken.
     */
    public synchronized boolean setSqlConnectionConfig(@NotNull SqlConnectionConfig config) {
        final boolean connectionChanged = !config.isEquivalent(this.sqlConnectionConfig);
        if (connectionChanged) {
            validateNewConfig(config);
            logger.info("DB connection configuration changed, (re)connecting without draining.");
            this.sqlConnectionConfig = config;
            setupPool();
        }
        return connectionChanged;
    }

    /**
     * Asynchronously updates the connection configuration, draining in-flight async tasks before
     * closing the old pool. Returns a future the caller can chain from.
     * <p>
     * Sequence of events on change:
     * <ol>
     * <li>Drain the async task queue, waiting up to {@code drainTimeout}/{@code drainTimeoutUnit}.</li>
     * <li>Fire {@link #onBeforePoolResetEvent()} (subscribers may perform last-chance cleanup).</li>
     * <li>Close the old pool and open a new one.</li>
     * <li>Invoke the {@link #afterPoolReset()} hook (subclasses run migrations and cache invalidation here).</li>
     * <li>Fire {@link #onAfterPoolResetEvent()} (subscribers may reinitialize).</li>
     * </ol>
     *
     * @param config           The new connection configuration.
     * @param drainTimeout     Maximum time to wait for in-flight tasks to complete.
     * @param drainTimeoutUnit Time unit for {@code drainTimeout}.
     * @return A future that resolves to {@code true} if the config changed and the pool was
     *         reset, {@code false} if the config was equivalent and no action was taken, or
     *         completes exceptionally if pool initialization fails.
     */
    public CompletableFuture<Boolean> setSqlConnectionConfig(
            @NotNull SqlConnectionConfig config,
            long drainTimeout,
            @NotNull TimeUnit drainTimeoutUnit
    ) {
        final CompletableFuture<Boolean> future;
        synchronized (this) {
            if (config.isEquivalent(this.sqlConnectionConfig)) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> pending = pendingPoolReset;
            if (pending != null && !pending.isDone()) {
                if (!config.isEquivalent(this.sqlConnectionConfig)) {
                    logger.severe("setSqlConnectionConfig called with a conflicting configuration while a pool reset"
                            + " to '" + this.sqlConnectionConfig.connectionId() + "' is already in progress."
                            + " The new configuration '" + config.connectionId() + "' will be ignored."
                            + " Chain from the returned future to sequence a subsequent reconfiguration.");
                } else {
                    logger.warning("setSqlConnectionConfig called while a pool reset is already in progress;"
                            + " returning existing future.");
                }
                return pending;
            }
            logger.info("DB connection configuration changed, (re)connecting asynchronously.");
            validateNewConfig(config);
            this.sqlConnectionConfig = config;
            future = new CompletableFuture<>();
            pendingPoolReset = future;
        }
        Thread.ofVirtual().start(() -> {
            try {
                if (asyncExecutor.isBusy()) {
                    logger.warning(String.format(
                            "Resetting pool while %d tasks are running and %d are queued;" +
                            " waiting up to %d %s for them to finish...",
                            asyncExecutor.getCurrentlyRunning(), asyncExecutor.getUnsubmittedTaskCount(),
                            drainTimeout, drainTimeoutUnit.name().toLowerCase()));
                    try {
                        if (!asyncExecutor.awaitIdle(drainTimeout, drainTimeoutUnit)) {
                            logger.severe(String.format(
                                    "Pool reset with %d tasks still running and %d still queued after drain timeout.",
                                    asyncExecutor.getCurrentlyRunning(), asyncExecutor.getUnsubmittedTaskCount()));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warning("Interrupted while draining; proceeding with pool reset.");
                    }
                }
                setupPool();
                future.complete(true);
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            } finally {
                synchronized (SqlClient.this) {
                    if (pendingPoolReset == future) pendingPoolReset = null;
                }
            }
        });
        return future;
    }

    /**
     * Fired just before the existing pool is closed during a pool reset (not initial construction).
     * Subscribers may use this to perform last-chance cleanup on the old connections.
     * @see #onAfterPoolResetEvent()
     */
    public SubscribableEvent<SqlClient> onBeforePoolResetEvent() { return onBeforePoolResetEvent; }

    /**
     * Fired after the new pool is ready and {@link #afterPoolReset()} has completed.
     * Subscribers may use this to reinitialize state that depends on an active connection.
     * @see #onBeforePoolResetEvent()
     */
    public SubscribableEvent<SqlClient> onAfterPoolResetEvent() { return onAfterPoolResetEvent; }

    /**
     * Fired inside {@link #validateNewConfig} when the incoming configuration carries a different
     * SQL dialect than the current one. Subscribers receive the incoming dialect and may throw
     * {@link IllegalStateException} to reject the change. Not fired on initial pool construction
     * or when the dialect is unchanged.
     * @see #onBeforePoolResetEvent()
     */
    public SubscribableEvent<SqlDialect> onBeforeDialectChangeEvent() { return onBeforeDialectChangeEvent; }

    /**
     * Hook called after the new pool is successfully opened, but before
     * {@link #onAfterPoolResetEvent()} fires. Subclasses should override this to perform
     * post-reset work (e.g., running migrations, invalidating repository caches) that must
     * complete before external subscribers are notified.
     * <p>Only called on actual pool resets, not during initial pool creation.</p>
     */
    protected void afterPoolReset() {}

    /**
     * Hook invoked when the connection configuration is about to change, before the pool is reset.
     * The default implementation fires {@link #onBeforeDialectChangeEvent()} when the dialect differs.
     * Subclasses may override to add further validation; they should call {@code super} first.
     *
     * @param newConfig The incoming configuration.
     * @throws IllegalStateException If the configuration change should be rejected.
     */
    protected void validateNewConfig(@NotNull SqlConnectionConfig newConfig) {
        if (sqlConnectionConfig != null && newConfig.dialect() != sqlConnectionConfig.dialect()) {
            onBeforeDialectChangeEvent.accept(newConfig.dialect());
        }
    }

    /** @return The current database configuration object. */
    public @NotNull SqlConnectionConfig getSqlConnectionConfig() {
        return sqlConnectionConfig;
    }

    /**
     * Borrow a connection from the pool.
     * The caller <b>MUST</b> use this inside a {@code try-with-resources} block to ensure
     * the connection is returned to the pool.
     * @return A valid {@link Connection}.
     * @throws DatabaseException If the DataSource is uninitialized or a connection cannot be obtained.
     */
    public @NotNull Connection getConnection() throws DatabaseException {
        if (dataSource == null) throw new DatabaseException("DataSource is not initialized!");
        try {
            return dataSource.getConnection();
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }

    /**
     * Configures and initializes the HikariCP connection pool.<br/>
     * <b>Optimization Details:</b>
     * <ul>
     * <li><b>MySQL:</b> Enables server-side prepared statement caching for performance.</li>
     * <li><b>SQLite:</b> Forces a {@code MaximumPoolSize} of 1 to prevent "Database is locked"
     * errors caused by concurrent writes in SQLite.</li>
     * </ul>
     */
    protected synchronized void setupPool() {
        final boolean isReset = dataSource != null && !dataSource.isClosed();
        if (isReset) {
            onBeforePoolResetEvent.accept(this);
            dataSource.close();
        }
        activeDialect = sqlConnectionConfig.dialect();
        if (activeDialect == SqlDialect.UNDEFINED) {
            logger.severe("Invalid syntax mode, DB will not be available!");
            return;
        }

        HikariConfig hikariConfig = new HikariConfig();
        sqlConnectionConfig.configurePool(hikariConfig);

        int maxConns = sqlConnectionConfig.maxConnections();
        if (maxConns <= 2) {
            asyncExecutor.setMaxConcurrency(1);
        } else if (maxConns <= 4) {
            asyncExecutor.setMaxConcurrency(2);
        } else {
            asyncExecutor.setMaxConcurrency(maxConns - 2);
        }

        // e.g. "MyPlugin::MySQL::rot%10.0.50.99/mydb" or "MyPlugin::SQLite::/var/data/mydb.db"
        String poolName = platformHandle.name() + "::" + activeDialect + "::" + sqlConnectionConfig.connectionId();

        hikariConfig.setPoolName(poolName);
        hikariConfig.setConnectionTimeout(TimeUnit.SECONDS.toMillis(30));

        try {
            this.dataSource = hikariCreator.apply(hikariConfig);
            logger.info("Database pool '" + poolName + "' initialized using " + sqlConnectionConfig);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize database pool!", e);
            return;
        }

        if (isReset) {
            afterPoolReset();
            onAfterPoolResetEvent.accept(this);
        }
    }

    /**
     * Blocks until all async tasks have completed execution then closes the connection pool
     * and releases all underlying physical connections.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     */
    public void shutdown(long timeout, @NotNull TimeUnit unit) {
        logger.info("Shutting down...");
        logger.fine("Shutting down async executor...");
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(timeout, unit)) {
                logger.severe("Timed out waiting for async db tasks to finish! Severing connection (data loss likely)!");
            } else {
                logger.fine("Async executor terminated within timeout.");
            }
        } catch (InterruptedException ex) {
            logger.log(Level.SEVERE, "Error while shutting down!", ex);
        }

        if (dataSource != null) {
            logger.fine("Closing DataSource...");
            dataSource.close();
            logger.info("DataSource closed.");
        }
        logger.info("Shutdown complete.");
    }

    //<editor-fold desc="Table metadata helpers" defaultstate="collapsed">
    /**
     * Inspects database metadata to check if a table exists.
     * @param tableName The name of the table (case-sensitivity depends on the DB driver).
     * @return true if the table exists.
     */
    public boolean tableExists(String tableName) {
        try (Connection conn = this.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getTables(null, null, tableName, null)) {
                return rs.next();
            }
        } catch(SQLException ex) {
            logger.log(Level.SEVERE, "Failure while checking if table `" + tableName + "` exists!", ex);
        }
        return false;
    }

    /** @return true if the table cannot be found in the database metadata. */
    public boolean tableDoesNotExist(String tableName) {
        return !tableExists(tableName);
    }

    /**
     * Checks if a specific column exists in a specific table.
     * @param tableName The table to check.
     * @param columnName The column to verify.
     * @return true if the column exists in the table.
     */
    public boolean tableHasColumn(@NotNull String tableName, @NotNull String columnName) {
        try (Connection conn = this.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getColumns(null, null, tableName, columnName)) {
                return rs.next();
            }
        } catch(SQLException ex) {
            logger.log(Level.SEVERE, "Failure while checking if table `" + tableName + "` has column `" + columnName + "`!", ex);
        }
        return true;
    }
    //</editor-fold>

    /**
     * Executes a non-query SQL statement (INSERT, UPDATE, DELETE, or DDL).
     *
     * @param sql The SQL string with '?' placeholders.
     * @param params arguments to fill placeholders.
     * @return The row count for DML statements, or 0 for SQL statements that return nothing.
     * @throws DatabaseException if a database access error occurs.
     * @see #executeTransaction(ThrowingFunction)
     * @see #executeSession(ThrowingFunction)
     */
    public final int executeUpdate(@NotNull String sql, Object... params) throws DatabaseException {
        try (Connection conn = this.getConnection(); PreparedStatement ps = prepareStatement(conn, sql, params)) {
            return ps.executeUpdate();
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }

    /**
     * Executes a DML statement (INSERT, UPDATE, DELETE) asynchronously.
     *
     * @param sql    The SQL string.
     * @param params Query arguments.
     * @return A future that completes with the number of affected rows.
     * @see #executeTransaction(ThrowingFunction)
     * @see #executeSession(ThrowingFunction)
     */
    public CompletableFuture<Integer> executeUpdateAsync(@NotNull String sql, Object... params) {
        return CompletableFuture.supplyAsync(() -> executeUpdate(sql, params), asyncExecutor);
    }

    /**
     * Executes an {@link UpsertStatement}. Generally an upsert is an atomic action meaning it either
     * succeeds and applies all changes or fails and makes no changes. There is no need to run a single
     * upsert in a transaction.
     * <p>
     * To execute a batch of upserts pass the result of {@link SqlClient#sql(UpsertStatement)} or
     * {@code upsert.sql(dbManager.activeDialect())} to one
     * of the batch methods on {@link ExecutionContext} within an {@link #executeTransaction(ThrowingFunction)}
     * action.
     * </p>
     *
     * @param upsert The {@link UpsertStatement} to execute.
     * @param params arguments to fill placeholders.
     * @return Row count.
     * @throws DatabaseException if a database access error occurs.
     */
    public final int executeUpsert(
            @NotNull UpsertStatement upsert,
            Object... params
    ) throws DatabaseException {
        return executeUpdate(upsert.sql(sqlConnectionConfig.dialect()), params);
    }

    /**
     * Executes an {@link UpsertStatement}. Generally an upsert is an atomic action meaning it either
     * succeeds and applies all changes or fails and makes no changes. There is no need to run a single
     * upsert in a transaction.
     * <p>
     * To execute a batch of upserts pass the result of {@link SqlClient#sql(UpsertStatement)} or
     * {@code upsert.sql(dbManager.activeDialect())} to one
     * of the batch methods on {@link ExecutionContext} within an {@link #executeTransactionAsync(ThrowingFunction)}
     * action.
     * </p>
     *
     * @param upsert The {@link UpsertStatement} to execute.
     * @param params arguments to fill placeholders.
     * @return Row count.
     * @throws DatabaseException if a database access error occurs.
     */
    public CompletableFuture<Integer> executeUpsertAsync(
            @NotNull UpsertStatement upsert,
            Object... params
    ) throws DatabaseException {
        return CompletableFuture.supplyAsync(() -> executeUpdate(upsert.sql(sqlConnectionConfig.dialect()), params), asyncExecutor);
    }

    /**
     * Resolves the dialect-specific SQL string for the given {@link UpsertStatement} using
     * the currently active dialect.
     * <p>Convenience wrapper around {@link UpsertStatement#sql(SqlDialect)}.</p>
     *
     * @param upsert The upsert statement to resolve.
     * @return The SQL string for the active dialect.
     */
    public String sql(@NotNull UpsertStatement upsert) {
        return upsert.sql(sqlConnectionConfig.dialect());
    }

    /**
     * Utility to create a {@link PreparedStatement} and map parameters to their respective indices.
     * @param conn An active connection.
     * @param sql The SQL string.
     * @param params Objects to map to the statement placeholders.
     * @throws DatabaseException If the query preparation fails.
     * @return A ready-to-execute PreparedStatement.
     * @see StatementExecutor
     */
    public PreparedStatement prepareStatement(
            @NotNull Connection conn,
            @NotNull String sql,
            Object... params
    ) throws DatabaseException {
        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            StatementExecutor.setParams(ps, params);
            return ps;
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }

    /**
     * Executes a query and maps the result to a usable object.
     * <p>
     * <b>WARNING:</b> The mapper function is executed while the database connection is still active.
     * DO NOT call other {@link SqlClient} methods from within the mapper, as this will cause a deadlock on
     * SQLite (pool size 1) or pool exhaustion on MySQL. Perform subsequent database tasks outside the callback
     * or use {@link #executeTransaction(ThrowingFunction)} or {@link #executeSession(ThrowingFunction)} instead.
     * </p>
     * <p>
     * <b>WARNING:</b> the mapper must not return or leak the provided ResultSet, it should fully consume the
     * ResultSet into a DTO or Collection before returning. The ResultSet will be closed after {@code mapper} returns.
     * </p>
     * @param sql The SELECT statement.
     * @param mapper A function that processes the ResultSet and returns a value (e.g., a List or DTO).
     * @param params Query arguments.
     * @return A wrapped result set containing the connection, statement, and result set.
     * @throws DatabaseException If the query fails.
     * @see #executeTransaction(ThrowingFunction)
     * @see #executeSession(ThrowingFunction)
     */
    public final <T> T executeQuery(
            @NotNull String sql,
            @NotNull ThrowingFunction<@NotNull ResultSet, T> mapper,
            Object... params
    ) throws DatabaseException {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            StatementExecutor.setParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return mapper.apply(rs);
            }
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        }
    }

    /**
     * Executes a query asynchronously and maps the result to a usable object.
     * <p>
     * The Connection and ResultSet are closed automatically immediately after the mapper function finishes.
     * </p>
     * <p>
     * <b>WARNING:</b> The callback will be executed by the same pooled {@code VirtualThread} used to run the
     * query and should not perform long blocking actions on this thread.
     * </p>
     * <p>
     * <b>WARNING:</b> The mapper function is executed while the database connection is still active.
     * DO NOT call other {@link SqlClient} methods from within the mapper, as this will cause a deadlock on
     * SQLite (pool size 1) or pool exhaustion on MySQL. Perform subsequent database tasks in the {@code thenAccept}
     * or {@code thenCompose} stages of the returned Future or prefer to use {@link #executeTransaction(ThrowingFunction)}
     * instead.
     * </p>
     *
     * @param sql    The SQL string.
     * @param mapper A function that processes the ResultSet and returns a value (e.g., a List or DTO).
     * @param params Query arguments.
     * @return A future that completes with the result of the mapper function.
     * @see #executeTransactionAsync(ThrowingFunction)
     * @see #executeSessionAsync(ThrowingFunction)
     */
    public <T> CompletableFuture<T> executeQueryAsync(
            @NotNull String sql,
            @NotNull ThrowingFunction<@NotNull ResultSet, T> mapper,
            Object... params
    ) {
        return CompletableFuture.supplyAsync(() -> executeQuery(sql, mapper, params), asyncExecutor);
    }

    /**
     * Provides a connection object which can be used to execute a series of database operations on a single
     * borrowed connection. The passed connection object is released for you.
     * <p>
     * This method is similar to {@link #executeTransaction(ThrowingFunction)} except that the connection
     * used is set to {@code auto-commit = true} so that each command executes immediately.
     * </p>
     * <p>Usage Example:</p>
     * <pre>{@code
     * db.executeSession(ctx -> {
     *     // 1. Read something
     *     double balance = ctx.executeQuery(
     *         "SELECT balance FROM users WHERE id = ?",
     *         rs -> rs.next() ? rs.getDouble("balance") : 0.0,
     *         userId);
     *     if (balance > 10) {
     *         // 2. Update something using the SAME connection
     *         ctx.executeUpdate("UPDATE users SET balance = balance - 10 WHERE id = ?", userId);
     *     }
     *     return null;
     * });
     * }</pre>
     *
     * @param action A function provided a {@link ExecutionContext} for easy statement execution.
     * @param <T>    The return type of the operation.
     * @return The result of the action.
     * @throws DatabaseException if a database access error occurs.
     * @see #executeTransaction(ThrowingFunction)
     */
    public <T> T executeSession(
            @NotNull ThrowingFunction<@NotNull ExecutionContext, T> action
    ) throws DatabaseException {
        Connection conn = null;
        try {
            conn = this.getConnection();
            ExecutionContext context = new ExecutionContext(conn, activeDialect);
            return action.apply(context);
        } catch (Throwable ex) {
            throw DatabaseException.wrap(ex);
        } finally {
            if (conn != null) {
                try {
                    // Ensure the connection is returned to the pool in a safe state
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {}
                try {
                    conn.close();
                } catch (SQLException ignored) {}
            }
        }
    }


    /**
     * Provides a connection object which can be used to asynchronously execute a series of database operations on a
     * single borrowed connection without risking a deadlock in a single-connection environment like SQLite.The passed
     * connection object is released for you.
     * <p>
     * This method is similar to {@link #executeTransactionAsync(ThrowingFunction)} except that the connection
     * used is set to {@code auto-commit = true}.
     * </p>
     * Usage Example: TODO update
     * <pre>{@code
     * db.executeSessionAsync(conn -> {
     *     // 1. Read something
     *     try (PreparedStatement ps1 = db.prepareStatement(conn,
     *          "SELECT balance FROM users WHERE id = ?", 1);
     *          ResultSet rs = ps1.executeQuery()
     *     ) {
     *         if (rs.next() && rs.getDouble("balance") > 10) {
     *             // 2. Update something using the SAME connection
     *             try (PreparedStatement ps2 = db.prepareStatement(conn,
     *                 "UPDATE users SET balance = balance - 10 WHERE id = ?", 1)
     *             ) {
     *                 ps2.executeUpdate();
     *             }
     *         }
     *     }
     *     return null;
     * });
     * }</pre>
     *
     * @param action A function that is provided an active {@link Connection}.
     * @param <T>    The return type of the operation.
     * @return A future containing the result of the session.
     * @see #executeTransactionAsync(ThrowingFunction)
     * @implNote Do not manually call {@link ExecutionContext#commit()} within the transaction block unless you are
     * implementing a nested sub-transaction strategy, as the manager handles the final commit/rollback automatically.
     */
    public <T> CompletableFuture<T> executeSessionAsync(
            @NotNull ThrowingFunction<@NotNull ExecutionContext, T> action
    ) {
        return CompletableFuture.supplyAsync(() -> executeSession(action), asyncExecutor);
    }

    /**
     * Executes a series of database operations within a single SQL Transaction using a helper context.
     * {@link Connection#commit()} is called following the return of {@code action}. In the event
     * {@code action} throws, {@link Connection#rollback()} will be called.
     * <p>
     * This method is similar to {@link #executeSession(ThrowingFunction)} except that the connection
     * used is set to {@code auto-commit = false}.
     * </p>
     *
     * Usage Example:
     * <pre>{@code
     * try {
     *     int bal = db.executeTransaction(ctx -> {
     *         // These both use the SAME connection automatically
     *         ctx.executeUpdate("UPDATE bank SET bal = bal - ? WHERE id = ?", 50.0, 1);
     *         ctx.executeUpdate("UPDATE bank SET bal = bal + ? WHERE id = ?", 50.0, 2);
     *
     *         // You can even perform a check mid-transaction
     *         double newBal = ctx.executeQuery("SELECT bal FROM bank WHERE id = ?", rs -> {
     *             return rs.next() ? rs.getDouble("bal") : 0.0;
     *         }, 1);
     *
     *         if (newBal < 0) throw new RuntimeException("Insufficient funds!");
     *
     *         return newBal;
     *     });
     *     logger.info("Transaction successful. New balance: " + bal)
     * } catch (SQLException ex) {
     *     plugin.getLogger().severe("Transfer failed: " + ex.getMessage());
     * }
     * }</pre>
     * @param action A function provided a {@link ExecutionContext} for easy statement execution.
     * @param <T>    The return type of the transaction logic.
     * @return A future that completes with the result of the transaction.
     * @throws DatabaseException if a database access error occurs.
     * @implNote Do not manually call {@link ExecutionContext#commit()} within the transaction block unless you are
     * implementing a nested sub-transaction strategy, as the manager handles the final commit/rollback automatically.
     */
    public <T> T executeTransaction(
            @NotNull ThrowingFunction<@NotNull ExecutionContext, T> action
    ) throws DatabaseException {
        Connection conn = null;
        try {
            conn = this.getConnection();
            conn.setAutoCommit(false);
            ExecutionContext context = new ExecutionContext(conn, activeDialect);
            T result = action.apply(context);
            conn.commit();
            return result;
        } catch (Throwable ex) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex2) {
                    ex.addSuppressed(ex);
                    throw new DatabaseException("Rollback failed! " + ex2.getMessage(), ex);
                }
            }
            throw DatabaseException.wrap(ex);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {}
                try {
                    conn.close();
                } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * Executes a series of database operations within a single SQL Transaction using a helper context.
     * {@link Connection#commit()} is called following the return of {@code action}. In the event
     * {@code action} throws, {@link Connection#rollback()} will be called.
     * <p>
     * This method is similar to {@link #executeSessionAsync(ThrowingFunction)} except that the connection
     * used is set to {@code auto-commit = false}.
     * </p>
     *
     * Usage Example:
     * <pre>{@code
     * db.executeTransactionAsync(ctx -> {
     *     // These both use the SAME connection automatically
     *     ctx.executeUpdate("UPDATE bank SET bal = bal - ? WHERE id = ?", 50.0, 1);
     *     ctx.executeUpdate("UPDATE bank SET bal = bal + ? WHERE id = ?", 50.0, 2);
     *
     *     // You can even perform a check mid-transaction
     *     double newBal = ctx.executeQuery("SELECT bal FROM bank WHERE id = ?", rs -> {
     *         return rs.next() ? rs.getDouble("bal") : 0.0;
     *     }, 1);
     *
     *     if (newBal < 0) throw new RuntimeException("Insufficient funds!");
     *
     *     return newBal;
     * }).thenAccept(bal -> {
     *     logger.info("Transaction successful. New balance: " + bal)
     * }).exceptionally(ex -> {
     *     plugin.getLogger().severe("Transfer failed: " + ex.getMessage());
     *     return null;
     * });
     * }</pre>
     * @param action A function providing a {@link ExecutionContext} for easy statement execution.
     * @param <T>    The return type of the transaction logic.
     * @return A future that completes with the result of the transaction.
     */
    public <T> CompletableFuture<T> executeTransactionAsync(
            @NotNull ThrowingFunction<@NotNull ExecutionContext, T> action
    ) {
        return CompletableFuture.supplyAsync(() -> executeTransaction(action), asyncExecutor);
    }

    /**
     * Executes a single SQL statement multiple times with different parameter sets.
     * <p>
     * This is significantly faster than calling {@code executeUpdate} in a loop,
     * as it reduces round-trips to the database and allows the driver to optimize.
     * </p>
     *
     * Usage Example:
     * <pre>{@code
     * List<Object[]> batchData = new ArrayList<>();
     * for (Player p : Bukkit.getOnlinePlayers()) {
     *     batchData.add(new Object[]{ p.getUniqueId().toString(), p.getName(), System.currentTimeMillis() });
     * }
     *
     * try {
     *     db.executeBatch("INSERT INTO player_log (uuid, name, time) VALUES (?, ?, ?)", batchData);
     *     logger.info("Batch complete. Processed " + results.length + " entries.");
     * } catch (CompletionException ex) {
     *     plugin.getLogger().severe("Batch failed: " + ex.getMessage());
     * }
     * }</pre>
     *
     * @param sql        The SQL string (e.g., "INSERT INTO logs (msg) VALUES (?)")
     * @param batchArgs  A collection where each element is an Object array of parameters.
     * @return An array of integers containing the number of rows affected for each execution.
     * @throws DatabaseException if a database access error occurs.
     */
    public int[] executeBatch(
            @NotNull String sql,
            @Nullable Collection<Object[]> batchArgs
    ) throws DatabaseException {
        return executeTransaction(ctx -> ctx.executeBatch(sql, batchArgs));
    }

    /**
     * Executes a single SQL statement multiple times with different parameter sets.
     * <p>
     * This is significantly faster than calling {@code executeUpdate} in a loop,
     * as it reduces round-trips to the database and allows the driver to optimize.
     * </p>
     *
     * Usage Example:
     * <pre>{@code
     * List<Object[]> batchData = new ArrayList<>();
     * for (Player p : Bukkit.getOnlinePlayers()) {
     *     batchData.add(new Object[]{ p.getUniqueId().toString(), p.getName(), System.currentTimeMillis() });
     * }
     *
     * try {
     *     db.executeBatch("INSERT INTO player_log (uuid, name, time) VALUES (?, ?, ?)", batchData);
     *     logger.info("Batch complete. Processed " + results.length + " entries.");
     * } catch (CompletionException ex) {
     *     plugin.getLogger().severe("Batch failed: " + ex.getMessage());
     * }
     * }</pre>
     *
     * @param sql        The SQL string (e.g., "INSERT INTO logs (msg) VALUES (?)")
     * @param batchArgs  A collection where each element is an Object array of parameters.
     * @return Future providing an array of integers containing the number of rows affected for each execution.
     */
    public CompletableFuture<int[]> executeBatchAsync(
            @NotNull String sql,
            @Nullable Collection<Object[]> batchArgs
    ) {
        return CompletableFuture.supplyAsync(() -> executeBatch(sql, batchArgs), asyncExecutor);
    }

    /**
     * Executes a single SQL statement multiple times with different parameter sets.
     * <p>
     * This is significantly faster than calling {@code executeUpdate} in a loop,
     * as it reduces round-trips to the database and allows the driver to optimize.
     * </p>
     *
     * @param sql        The SQL string (e.g., "INSERT INTO logs (msg) VALUES (?)")
     * @param batchObjects  A collection where each element will be passed to the {@code mapper}.
     * @param mapper Function to convert each batchObject to an Object[] to pass to the sql statement.
     *               The first argument is the object to source values from and the second argument is the
     *               {@code Object[]} to populate query params to be used for the given obj.
     * @param <T> Object type.
     * @return An array of integers containing the number of rows affected for each execution.
     * @throws DatabaseException if a database access error occurs.
     */
    public <T> int[] executeBatch(
            @NotNull String sql,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<T, @NotNull Object[]> mapper
    ) throws DatabaseException {
        return executeTransaction(ctx -> ctx.executeBatch(sql, batchObjects, mapper));
    }

    /**
     * Executes a single SQL statement multiple times with different parameter sets.
     * <p>
     * This is significantly faster than calling {@code executeUpdate} in a loop,
     * as it reduces round-trips to the database and allows the driver to optimize.
     * </p>
     *
     * @param sql        The SQL string (e.g., "INSERT INTO logs (msg) VALUES (?)")
     * @param batchObjects  A collection where each element will be passed to the {@code mapper}.
     * @param mapper Function to convert each batchObject to an Object[] to pass to the sql statement.
     *               The first argument is the object to source values from and the second argument is the
     *               {@code Object[]} to populate query params to be used for the given obj.
     * @param <T> Object type.
     * @return Future providing an array of integers containing the number of rows affected for each execution.
     */
    public <T> CompletableFuture<int[]> executeBatchAsync(
            @NotNull String sql,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<T, Object[]> mapper
    ) {
        return CompletableFuture.supplyAsync(() -> executeTransaction(ctx -> ctx.executeBatch(sql, batchObjects, mapper)), asyncExecutor);
    }

    /**
     * Executes a single batch of updates. The batch will execute within a transaction.
     *
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
    public <T> int[] executeChunkedBatch(
            int maxChunkingSize,
            @NotNull String sql,
            @Nullable Collection<Object[]> batchArgs
    ) throws ChunkedBatchExecutionException {
        return executeSession(ctx -> ctx.executeChunkedBatch(maxChunkingSize, sql, batchArgs));
    }


    /**
     * Executes a single batch of updates. The batch will execute within a transaction.
     *
     * <p><b>Warning:</b> it's strongly advised to use {@link #executeChunkedBatchAsync(int, String, Collection, ThrowingBiConsumer)}
     * when performing more than about 5k updates at a time.</p>
     *
     * @param sql The SQL string.
     * @param batchArgs  A collection where each element is an Object array of parameters.
     *                   Collection MUST NOT contain null elements.
     * @return Future providing an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.<br/>
     *   If the operation failed, leverage the information provided by {@link ChunkedBatchExecutionException}
     *   about processing progress allowing you to recover from a partially commited batch.
     */
    public <T> CompletableFuture<int[]> executeChunkedBatchAsync(
            int maxChunkingSize,
            @NotNull String sql,
            @Nullable Collection<Object[]> batchArgs
    ) {
        return CompletableFuture.supplyAsync(() -> executeSession(ctx -> ctx.executeChunkedBatch(maxChunkingSize, sql, batchArgs)), asyncExecutor);
    }

    /**
     * Splits updates in multiple batches to balance performance and database resource usage. Each chunk will execute
     * within a transaction and call {@code commit()} after every chunk. This provides the benefits of executing a
     * batch within a transaction while also preventing the database undo/redo logs from growing indefinitely.<br/>
     * However, if a chunk fails, that one chunk will be rolled back, all prior chunks will have been commited to the
     * database, and no further chunks will be processed.
     *
     * @param maxChunkingSize Max items per batch (500-1000 recommended, up to 5000 is generally OK).
     * @param sql The SQL string.
     * @param batchObjects The items to process.
     * @param mapper       The function to map each item to SQL parameters.
     * @return an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.
     * @throws ChunkedBatchExecutionException if any chunk fails. This exception provides details
     *   about processing progress allowing you to recover from a partially commited batch.
     */
    public <T> int[] executeChunkedBatch(
            int maxChunkingSize,
            @NotNull String sql,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<T, Object[]> mapper
    ) throws ChunkedBatchExecutionException {
        return executeSession(ctx -> ctx.executeChunkedBatch(maxChunkingSize, sql, batchObjects, mapper));
    }

    /**
     * Splits updates in multiple batches to balance performance and database resource usage. Each chunk will execute
     * within a transaction and call {@code commit()} after every chunk. This provides the benefits of executing a
     * batch within a transaction while also preventing the database undo/redo logs from growing indefinitely.<br/>
     * However, if a chunk fails, that one chunk will be rolled back, all prior chunks will have been commited to the
     * database, and no further chunks will be processed.
     *
     * @param maxChunkingSize Max items per batch (500-1000 recommended, up to 5000 is generally OK).
     * @param sql The SQL string.
     * @param batchObjects The items to process.
     * @param mapper       The function to map each item to SQL parameters.
     * @return Future providing an array of update counts containing one element for each command in the total batch.
     *   The elements of the array are ordered according to the order in which commands were added to the batch.<br/>
     *   If the operation failed, leverage the information provided by {@link ChunkedBatchExecutionException}
     *   about processing progress allowing you to recover from a partially commited batch.
     */
    public <T> CompletableFuture<int[]> executeChunkedBatchAsync(
            int maxChunkingSize,
            @NotNull String sql,
            @Nullable Collection<T> batchObjects,
            @NotNull ThrowingBiConsumer<T, Object[]> mapper
    ) {
        return CompletableFuture.supplyAsync(() -> executeSession(ctx -> ctx.executeChunkedBatch(maxChunkingSize, sql, batchObjects, mapper)), asyncExecutor);
    }
}
