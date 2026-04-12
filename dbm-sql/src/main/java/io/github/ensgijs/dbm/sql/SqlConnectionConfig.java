package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates everything needed to connect to a SQL database and initialize a HikariCP pool.
 * <p>
 * Rather than carrying all possible fields for every dialect in one flat structure, this
 * interface lets each dialect expose only the fields relevant to it:
 * </p>
 * <ul>
 * <li>{@link MySqlConnectionConfig} — MySQL (or compatible) server connections.</li>
 * <li>{@link SqliteConnectionConfig} — SQLite file-based connections.</li>
 * </ul>
 * <p>
 * Implement this interface to add support for a new dialect. See {@link SqlDialect} for
 * the complete list of additional touch-points that must be updated alongside a new implementation.
 * </p>
 */
public interface SqlConnectionConfig {

    /**
     * @return The fully-qualified JDBC connection URL for this configuration.
     */
    @NotNull String getDbUrl();

    /**
     * Applies all driver-specific and pool-specific settings to the provided
     * {@link HikariConfig}. This includes the JDBC URL, driver class name,
     * credentials, maximum pool size, and any performance-tuning data-source properties.
     * <p>
     * {@link SqlClient} calls this method during pool initialization. Custom implementations
     * may set any additional Hikari properties needed for their driver.
     * </p>
     *
     * @param config The HikariCP configuration to populate. Must not be null.
     */
    void configurePool(@NotNull HikariConfig config);

    /**
     * @return Maximum number of connections in the pool. For SQLite this is always {@code 1}.
     *         Used by {@link SqlClient} to calibrate async executor concurrency.
     */
    int maxConnections();

    /**
     * @return The SQL dialect this connection speaks.
     */
    @NotNull SqlDialect dialect();

    /**
     * Returns a short, human-readable identifier for this connection, used in pool names
     * and log messages. The dialect is included separately by the caller, so this should
     * return only connection-specific details.
     *
     * <p>Examples: {@code "user%host/database"} for MySQL,
     * {@code "/var/data/mydb.db"} for SQLite.</p>
     *
     * @return A compact connection identifier.
     */
    @NotNull String connectionId();

    /**
     * Dialect-aware equivalence check. Returns {@code true} if both configs represent the
     * same effective connection with the same settings. Used by {@link SqlClient} to
     * determine whether the pool needs to be rebuilt when the config is updated.
     *
     * @param other The other config to compare against. May be {@code null}.
     * @return {@code true} if equivalent; {@code false} otherwise.
     */
    boolean isEquivalent(@Nullable SqlConnectionConfig other);
}
