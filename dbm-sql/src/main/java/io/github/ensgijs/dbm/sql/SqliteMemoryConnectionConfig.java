package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A {@link SqlConnectionConfig} backed by a named, in-memory SQLite database.
 * <p>
 * Uses the {@code jdbc:sqlite:file:NAME?mode=memory&cache=shared} URI form so that multiple
 * HikariCP connections within the same JVM all address the same in-memory database.
 * The database lives as long as at least one connection to it remains open; closing the
 * HikariCP pool destroys all data.
 * </p>
 *
 * <p>Two instances with the same {@code databaseName} share state within the JVM.
 * Two instances with different names are fully isolated.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Auto-named — isolated from every other inMemory() call
 * SqlDatabaseManager manager = new SqlDatabaseManager(handle, SqliteConnectionConfig.inMemory());
 *
 * // Named — two configs with the same name share one in-memory database
 * SqliteMemoryConnectionConfig cfg = SqliteConnectionConfig.inMemory("test-db");
 * }</pre>
 *
 * @see SqliteConnectionConfig#inMemory()
 * @see SqliteConnectionConfig#inMemory(String)
 */
public record SqliteMemoryConnectionConfig(@NotNull String databaseName) implements SqlConnectionConfig {

    public SqliteMemoryConnectionConfig {
        Objects.requireNonNull(databaseName, "databaseName");
        if (databaseName.isBlank())
            throw new IllegalArgumentException("databaseName must not be blank");
    }

    /** Creates an auto-named instance — isolated from all other in-memory instances. */
    public SqliteMemoryConnectionConfig() {
        this("dbm_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public @NotNull String getDbUrl() {
        // file:NAME?mode=memory&cache=shared lets multiple Hikari connections within the JVM
        // address the same named in-memory database. Data persists while the pool is open.
        return "jdbc:sqlite:file:" + databaseName + "?mode=memory&cache=shared";
    }

    @Override
    public void configurePool(@NotNull HikariConfig config) {
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl(getDbUrl());
        config.setMaximumPoolSize(maxConnections());
    }

    /** Always {@code 1}; matches the single-writer discipline of the file-based SQLite config. */
    @Override
    public int maxConnections() {
        return 1;
    }

    @Override
    public @NotNull SqlDialect dialect() {
        return SqlDialect.SQLITE;
    }

    @Override
    public @NotNull String connectionId() {
        return "sqlite-mem:" + databaseName;
    }

    @Override
    public boolean isEquivalent(@Nullable SqlConnectionConfig other) {
        return other instanceof SqliteMemoryConnectionConfig o
                && o.databaseName.equals(databaseName);
    }

    @Override
    public String toString() {
        return "[SQLite in-memory]\ndatabaseName: " + databaseName;
    }
}
