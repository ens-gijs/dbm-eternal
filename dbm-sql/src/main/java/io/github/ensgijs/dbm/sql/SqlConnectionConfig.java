package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.util.objects.ObjectHelpers;
import io.github.ensgijs.dbm.platform.PlatformHandle;

import java.io.File;
import java.util.Objects;

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

//    /**
//     * Expected config block example:
//     * <pre>{@code
//     *   mode: SQLite  # one-of: SQLite, MySQL
//     *   database: MyDb  # Always required
//     *   mysql:
//     *     max-connections: 10
//     *     host: 127.0.0.1
//     *     port: 3306
//     *     username:
//     *     password:
//     * }</pre>>
//     */
//    public static SqlConnectionConfig fromConfig(ConfigAdapter config) {
//        return new SqlConnectionConfig(
//                ObjectHelpers.asEnum(config.getString("mode", null), SqlDialect.UNDEFINED),
//                Objects.requireNonNull(config.getString("database", null), "Missing required value for sql connection `database` field!").trim(),
//                config.getInt("mysql.max-connections", 10),
//                config.getString("mysql.host", "127.0.0.1").trim(),
//                config.getInt("mysql.port", 3306),
//                config.getString("mysql.username", "").trim(),
//                config.getString("mysql.password", "").trim()
//        );
//    }

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
//            return "jdbc:sqlite:plugins/" + plugin.getName() + "/" + database + ".db?journal_mode=WAL&synchronous=NORMAL";
        }
    }

    @Override
    public String toString() {
        return
                "[" + sqlDialect + "]\n" +
                        "database: " + database + "\n" +
                        "mysql.max-connections: " + maxConnections + "\n" +
                        "mysql.host: " + host + "\n" +
                        "mysql.port: " + port + "\n" +
                        "mysql.username: " + username + "\n" +
                        "mysql.password: " + (password != null && !password.isEmpty() ? "********" : "");
    }

    /// Similar to equals() but only compares fields applicable to the configured mode / dialect.
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