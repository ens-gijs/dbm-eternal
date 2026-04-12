/**
 * Core SQL layer providing connection pooling, statement execution, and batch operations.
 *
 * <h2>Entry Points</h2>
 * <ul>
 * <li>{@link io.github.ensgijs.dbm.sql.SqlClient} — the primary class for managing a HikariCP
 *     connection pool and executing SQL. Supports single updates, queries, transactions,
 *     sessions, and async variants of each.</li>
 * <li>{@link io.github.ensgijs.dbm.sql.SqlConnectionConfig} — immutable configuration record
 *     for MySQL and SQLite connections.</li>
 * <li>{@link io.github.ensgijs.dbm.sql.UpsertStatement} — a reusable, dialect-aware builder for
 *     {@code INSERT ... ON CONFLICT / ON DUPLICATE KEY UPDATE} statements.</li>
 * <li>{@link io.github.ensgijs.dbm.sql.ExecutionContext} — provided to transaction and session
 *     lambdas, giving access to a single borrowed connection with convenience execution methods.</li>
 * </ul>
 *
 * <h2>Exception Hierarchy</h2>
 * <ul>
 * <li>{@link io.github.ensgijs.dbm.sql.DatabaseException} — base for all library exceptions;
 *     extends {@link java.util.concurrent.CompletionException} for async compatibility.</li>
 * <li>{@link io.github.ensgijs.dbm.sql.ChunkedBatchExecutionException} — thrown when a chunked
 *     batch partially fails, carrying progress details to support recovery.</li>
 * <li>{@link io.github.ensgijs.dbm.sql.SqlStatementSplitter.StatementSplitException} — thrown
 *     for malformed SQL (unclosed quotes/comments).</li>
 * </ul>
 *
 * <h2>Dialects</h2>
 * <p>
 * {@link io.github.ensgijs.dbm.sql.SqlDialect} identifies the active database engine. SQLite
 * pools are automatically capped at one connection to avoid locking errors.
 * </p>
 */
package io.github.ensgijs.dbm.sql;
