package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * {@link SqlConnectionConfig} for MySQL (or compatible, e.g. MariaDB) server connections.
 * <p>
 * Configures HikariCP with the MySQL JDBC driver and server-side prepared-statement caching
 * for optimal performance. The pool size is clamped to a minimum of 1.
 * </p>
 *
 * @param host           MySQL server hostname or IP address.
 * @param port           MySQL server port (typically {@code 3306}).
 * @param database       The database (schema) name to connect to.
 * @param maxConnections Maximum connection pool size. Values below 1 are clamped to 1.
 * @param username       MySQL login username.
 * @param password       MySQL login password. May be {@code null} or empty.
 */
public record MySqlConnectionConfig(
        @NotNull String host,
        int port,
        @NotNull String database,
        int maxConnections,
        @NotNull String username,
        @Nullable String password
) implements SqlConnectionConfig {

    /** Compact constructor — validates required fields and clamps {@code maxConnections}. */
    public MySqlConnectionConfig {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(username, "username must not be null");
        maxConnections = Math.max(1, maxConnections);
    }

    @Override
    public @NotNull String getDbUrl() {
        // rewriteBatchedStatements=true: bundles batch entries into a single multi-row INSERT
        // instead of individual packets, which is exponentially faster for large batches.
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?autoReconnect=true&rewriteBatchedStatements=true";
    }

    @Override
    public void configurePool(@NotNull HikariConfig config) {
        config.setJdbcUrl(getDbUrl());
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        // Server-side prepared-statement cache — reduces parse overhead on repeated queries.
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.setMaximumPoolSize(maxConnections);
    }

    @Override
    public @NotNull SqlDialect dialect() {
        return SqlDialect.MYSQL;
    }

    @Override
    public @NotNull String connectionId() {
        return username + "%" + host + "/" + database;
    }

    @Override
    public boolean isEquivalent(@Nullable SqlConnectionConfig other) {
        if (!(other instanceof MySqlConnectionConfig that)) return false;
        return Objects.equals(database, that.database)
                && Objects.equals(host, that.host)
                && port == that.port
                && maxConnections == that.maxConnections
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password);
    }

    @Override
    public String toString() {
        return "[MySQL]\n"
                + "database: " + database + "\n"
                + "max-connections: " + maxConnections + "\n"
                + "host: " + host + "\n"
                + "port: " + port + "\n"
                + "username: " + username + "\n"
                + "password: " + (password != null && !password.isEmpty() ? "********" : "");
    }
}
