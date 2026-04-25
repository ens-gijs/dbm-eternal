package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A {@link SqlConnectionConfig} backed by an in-memory SQLite database.
 *
 * <p>
 * Each instance is fully isolated from other inMemory() instances. The database lives as long as
 * at least one connection to it remains open; closing the HikariCP pool destroys all data.
 * </p>
 *
 * <p>
 * Uses the {@code jdbc:sqlite:file:NAME?mode=memory} URI.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SqlClient client = new SqlClient(handle, SqliteConnectionConfig.inMemory());
 * }</pre>
 *
 * @see SqliteConnectionConfig#inMemory()
 */
public record SqliteMemoryConnectionConfig() implements SqlConnectionConfig {

    public @NotNull String databaseName() {
        return "M" + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public @NotNull String getDbUrl() {
        return "jdbc:sqlite:file:" + databaseName() + "?mode=memory";
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
        return "sqlite-mem:" + databaseName();
    }

    @Override
    public boolean isEquivalent(@Nullable SqlConnectionConfig other) {
        return other == this;
    }

    @Override
    public String toString() {
        return "[SQLite in-memory]\ndatabaseName: " + databaseName();
    }
}
