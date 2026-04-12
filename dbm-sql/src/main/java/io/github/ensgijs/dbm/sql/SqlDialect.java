package io.github.ensgijs.dbm.sql;

/**
 * Identifies the SQL database dialect in use.
 * <p>
 * The active dialect drives dialect-specific behavior throughout the library, including
 * connection URL construction, upsert syntax generation, and connection pool configuration.
 * </p>
 */
public enum SqlDialect {
    /** Sentinel value indicating no dialect has been configured. Operations requiring a dialect will fail. */
    UNDEFINED("UNDEFINED"),
    /** SQLite — single-writer file-based database; pool is automatically capped at 1 connection. */
    SQLITE("SQLite"),
    /** MySQL (or compatible, e.g. MariaDB) — multi-connection server-based database. */
    MYSQL("MySQL");

    private final String display;

    SqlDialect(String display) {
        this.display = display;
    }

    @Override
    public String toString() {
        return display;
    }
}