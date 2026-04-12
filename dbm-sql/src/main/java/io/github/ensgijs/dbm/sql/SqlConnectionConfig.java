package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.platform.PlatformHandle;

import java.io.File;
import java.util.Objects;

/**
 * Immutable configuration for a SQL database connection.
 * <p>
 * Holds all the parameters needed to initialize a HikariCP connection pool for either
 * a MySQL or SQLite database. MySQL-specific fields ({@code host}, {@code port},
 * {@code username}, {@code password}, {@code maxConnections}) are ignored when the
 * dialect is {@link SqlDialect#SQLITE}.
 * </p>
 *
 * @param sqlDialect     The database dialect. Must not be {@link SqlDialect#UNDEFINED}.
 * @param database       The database (or file base name for SQLite). Required.
 * @param maxConnections Maximum pool size for MySQL. Values below 1 are clamped to 1. Ignored for SQLite.
 * @param host           MySQL server hostname. Ignored for SQLite.
 * @param port           MySQL server port. Ignored for SQLite.
 * @param username       MySQL username. Ignored for SQLite.
 * @param password       MySQL password. Ignored for SQLite.
 */
public record SqlConnectionConfig (
        SqlDialect sqlDialect,
        String database,
        int maxConnections,
        String host,
        int port,
        String username,
        String password
) {

    public SqlConnectionConfig (
            SqlDialect sqlDialect,
            String database,
            int maxConnections,
            String host,
            int port,
            String username,
            String password
    ) {
        this.sqlDialect = sqlDialect;
        this.database = database;
        this.maxConnections = Math.max(1, maxConnections);
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    /**
     * Builds the JDBC connection URL for this configuration.
     * <p>
     * For SQLite, the database file is resolved relative to
     * {@link PlatformHandle#dataFolder()} as {@code <dataFolder>/<database>.db}.
     * </p>
     *
     * @param platformHandle Used to resolve the data folder for SQLite file paths.
     * @return A fully qualified JDBC URL ready for use with HikariCP.
     */
    public String getDbUrl(PlatformHandle platformHandle) {
        if (sqlDialect == SqlDialect.MYSQL) {
            // rewriteBatchedStatements=true
            //   Without this, the MySQL driver sends each batch entry as a separate packet. With this enabled,
            //   the driver bundles them into a single INSERT INTO ... VALUES (...), (...), (...) string, which
            //   is exponentially faster.
            return "jdbc:mysql://" + host + ":" + port + "/" + database + "?autoReconnect=true&rewriteBatchedStatements=true";
        } else {
            // Use the plugin's data folder to ensure the path is correct in all environments
            File dbFile = new File(platformHandle.dataFolder(), database + ".db");
            // journal_mode=WAL
            //   Enable Write-Ahead Logging (WAL) mode. This allows multiple readers and one writer to work
            //   simultaneously without blocking, and significantly increases write throughput.
            // synchronous=NORMAL
            //   Performance: Setting the mode to NORMAL significantly improves write performance compared to the
            //    default FULL mode because it reduces the number of times the database engine pauses to ensure
            //    data is written to the disk surface (fsync operations).
            //   Durability Trade-off: In NORMAL mode, SQLite synchronizes at "critical moments," but less often
            //    than in FULL mode.
            //    Application Crash: Data is still safe if the application itself crashes.
            //    Power Failure/OS Crash: There is a small chance that the most recent transactions might be
            //     rolled back and data lost following a power failure or hard operating system crash.
            //     The database, however, is guaranteed not to be corrupted.
            return "jdbc:sqlite:" + dbFile.getAbsolutePath() + "?journal_mode=WAL&synchronous=NORMAL";
        }
    }

    @Override
    public String toString() {
        if (sqlDialect == SqlDialect.SQLITE) {
            return "[" + sqlDialect + "]\n" +
                    "database: " + database;
        }
        return "[" + sqlDialect + "]\n" +
                "database: " + database + "\n" +
                "max-connections: " + maxConnections + "\n" +
                "host: " + host + "\n" +
                "port: " + port + "\n" +
                "username: " + username + "\n" +
                "password: " + (password != null && !password.isEmpty() ? "********" : "");
    }

    /**
     * Similar to {@link #equals(Object)} but only compares fields that are meaningful for the
     * configured dialect. For SQLite only the database name is compared; for MySQL all fields
     * are compared.
     *
     * @param that The other config to compare against.
     * @return {@code true} if both configs would connect to the same database with the same settings.
     */
    public boolean isEquivalent(SqlConnectionConfig that) {
        if (that == null || this.sqlDialect != that.sqlDialect) return false;
        if (this.sqlDialect == SqlDialect.SQLITE) {
            return this.database.equals(that.database);
        }
        return  Objects.equals(database, that.database)
                && port == that.port
                && maxConnections == that.maxConnections
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password);
    }
}