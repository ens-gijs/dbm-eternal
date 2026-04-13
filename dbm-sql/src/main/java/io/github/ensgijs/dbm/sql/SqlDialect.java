package io.github.ensgijs.dbm.sql;

/**
 * Identifies the SQL database dialect in use.
 * <p>
 * The active dialect drives dialect-specific behavior throughout the library, including
 * connection URL construction, upsert syntax generation, and connection pool configuration.
 * </p>
 *
 * <h2>Adding a new dialect</h2>
 * <p>The following touch-points must all be updated when adding a new dialect value:</p>
 * <ol>
 * <li><b>{@code SqlDialect}</b> (this file) — add the new enum constant.</li>
 * <li><b>{@link io.github.ensgijs.dbm.sql.SqlConnectionConfig}</b> — create a new
 *     {@code XxxConnectionConfig} record implementing {@code SqlConnectionConfig} that
 *     configures the HikariCP pool for the new driver.</li>
 * <li><b>{@link io.github.ensgijs.dbm.sql.UpsertStatement}</b> — add a new case in
 *     {@code sql(SqlDialect)} for the dialect's upsert syntax (if supported).</li>
 * <li><b>{@code io.github.ensgijs.dbm.migration.SchemaMigrator#ensureHistoryTable()}</b> -
 * add new case.</li>
 * <li><b>{@code Migration.MigrationSourceType}</b> — add
 *     a corresponding source-type enum value (e.g., {@code POSTGRES}).</li>
 * <li><b>{@code Migration.MigrationSource#sourceType()}</b> — add
 *     the new dialect→sourceType mapping in the switch expression.</li>
 * <li><b>{@code MigrationLoader.masterMigrationList}</b> — add a new
 *     {@code SqlDialect.XXX → new ConcurrentHashMap<>()} entry.</li>
 * <li><b>{@code MigrationLoader.loadMigrations()}</b> — add an {@code else if} branch for
 *     the new source type to call {@code putToMasterList} with the correct dialect.</li>
 * <li><b>{@code MigrationLoader.parseMigration()}</b> — add the new dialect case in the
 *     {@code switch} that maps source-type back to a {@link SqlDialect}.</li>
 * </ol>
 * <p>Note: the migration file-name pattern and the master-list reset helper both derive
 * from {@code SqlDialect.values()} and therefore update automatically.</p>
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