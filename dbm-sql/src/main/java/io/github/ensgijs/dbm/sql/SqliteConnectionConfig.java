package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;

/**
 * {@link SqlConnectionConfig} for SQLite file-based connections.
 * <p>
 * The pool is always capped at one connection to prevent {@code "database is locked"} errors
 * caused by concurrent writes, which SQLite does not support.
 * </p>
 * <p>
 * WAL (Write-Ahead Logging) mode and {@code synchronous=NORMAL} are enabled by default:
 * </p>
 * <ul>
 * <li><b>WAL:</b> allows concurrent readers and one writer without blocking each other,
 *     significantly improving write throughput.</li>
 * <li><b>synchronous=NORMAL:</b> reduces the number of expensive {@code fsync} calls.
 *     Data is safe on application crash; a small risk of the most recent transaction being
 *     rolled back exists only on an OS crash or power loss.</li>
 * </ul>
 *
 * @param file The SQLite database file. The file and any parent directories will be created
 *             automatically by the SQLite driver on first connection.
 */
public record SqliteConnectionConfig(@NotNull File file) implements SqlConnectionConfig {

    /** Compact constructor — validates that {@code file} is not null. */
    public SqliteConnectionConfig {
        Objects.requireNonNull(file, "file must not be null");
    }

    /**
     * Convenience factory that constructs the {@link File} from a folder and a bare database name.
     * The {@code .db} extension is appended automatically.
     *
     * <p>Usage example:</p>
     * <pre>{@code
     * SqliteConnectionConfig cfg = SqliteConnectionConfig.of(plugin.getDataFolder(), "mydb");
     * // resolves to: <dataFolder>/mydb.db
     * }</pre>
     *
     * @param folder       The directory in which to place the database file.
     * @param databaseName The base name of the database (without extension).
     * @return A new {@code SqliteConnectionConfig} pointing to {@code <folder>/<databaseName>.db}.
     */
    public static SqliteConnectionConfig of(@NotNull File folder, @NotNull String databaseName) {
        Objects.requireNonNull(folder, "folder must not be null");
        Objects.requireNonNull(databaseName, "databaseName must not be null");
        return new SqliteConnectionConfig(new File(folder, databaseName + ".db"));
    }

    @Override
    public @NotNull String getDbUrl() {
        return "jdbc:sqlite:" + file.getAbsolutePath() + "?journal_mode=WAL&synchronous=NORMAL";
    }

    @Override
    public void configurePool(@NotNull HikariConfig config) {
        config.setJdbcUrl(getDbUrl());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
    }

    /** Always returns {@code 1}; SQLite does not support concurrent writers. */
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
        return file.getAbsolutePath();
    }

    @Override
    public boolean isEquivalent(@Nullable SqlConnectionConfig other) {
        if (!(other instanceof SqliteConnectionConfig that)) return false;
        return file.getAbsolutePath().equals(that.file.getAbsolutePath());
    }

    @Override
    public String toString() {
        return "[SQLite]\n"
                + "file: " + file.getAbsolutePath();
    }
}
