package io.github.ensgijs.dbm.sql;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
/**
 * A stateful builder and cache for cross-dialect SQL "UPSERT" (Insert or Update) statements.
 * <p>
 * An upsert operation attempts to insert a row, but if a conflict occurs on a unique key or
 * primary key, it updates the existing row instead. This class abstracts the syntactical
 * differences between MySQL's {@code ON DUPLICATE KEY UPDATE} and SQLite's {@code ON CONFLICT DO UPDATE}.
 * </p>
 * <p><b>Performance Note:</b> This class is designed to be instantiated once per repository
 * and reused. It caches the generated SQL string for the last requested {@link SqlDialect}.
 * Frequent alternating calls between different dialects will invalidate the cache and
 * trigger string reconstruction.</p>
 */
public final class UpsertStatement {
    private final @NotNull String table;
    private final @NotNull String[] keyColumns;
    private final @NotNull String[] valueColumns;
    private SqlDialect cachedDialect = SqlDialect.UNDEFINED;
    private String cachedSql = "";

    /**
     * Constructs an UpsertStatement.
     * @param table        The target database table.
     * @param keyColumns   The columns that form the unique constraint (e.g., Primary Key).
     * These are used in the {@code WHERE}/{@code ON CONFLICT} clause.
     * @param valueColumns The columns to be updated if the record already exists.
     * These are also included in the initial {@code INSERT} values.
     * @see #builder()
     */
    public UpsertStatement(@NotNull String table, @NotNull String[] keyColumns, @NotNull String[] valueColumns) {
        this.table = table;
        this.keyColumns = keyColumns;
        this.valueColumns = valueColumns;
    }

    /**
     * Constructs an UpsertStatement with no specific update columns.
     * @param table      The target database table.
     * @param keyColumns The columns that form the unique constraint.
     * @see #builder()
     */
    public UpsertStatement(@NotNull String table, @NotNull String @NotNull... keyColumns) {
        this(table, keyColumns, new String[0]);
    }

    /**
     * Generates or retrieves the cached SQL string for the specified dialect.
     * <p><b>Parameter Order:</b> The generated SQL expects parameters in the
     * following order: {@code [keyColumns..., valueColumns...]}. Ensure your
     * execution time arguments match this sequence.</p>
     * <p><b>Thread Safety:</b> This method is <em>not</em> thread-safe. The most recently generated
     * SQL string is cached by dialect; concurrent calls from multiple threads may cause the cache
     * to be rebuilt unnecessarily. Instantiate one {@code UpsertStatement} per repository field
     * and call {@code sql()} from a single thread (or accept the minor regeneration overhead).</p>
     * @param dialect The target database dialect (MySQL or SQLite).
     * @return A formatted SQL string. Returns an empty string if the dialect is
     * {@code null} or {@link SqlDialect#UNDEFINED}.
     */
    public @NotNull String sql(@Nullable SqlDialect dialect) {
        if (cachedDialect == dialect)
            return cachedSql;
        if (dialect == null || dialect == SqlDialect.UNDEFINED)
            return "";

        cachedDialect = dialect;
        String allColumns = Stream.concat(Arrays.stream(keyColumns), Arrays.stream(valueColumns))
                .collect(Collectors.joining(", "));

        String placeholders = "?" + (", ?".repeat(keyColumns.length + valueColumns.length - 1));

        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(table).append(" (").append(allColumns).append(") ")
                .append("VALUES (").append(placeholders).append(") ");

        if (dialect == SqlDialect.MYSQL) {
            sql.append("ON DUPLICATE KEY UPDATE ");
            sql.append(Arrays.stream(valueColumns)
                    .map(c -> c + " = VALUES(" + c + ")")
                    .collect(Collectors.joining(", ")));
        } else if (dialect == SqlDialect.SQLITE) {
            sql.append("ON CONFLICT(").append(String.join(", ", keyColumns)).append(") DO UPDATE SET ");
            sql.append(Arrays.stream(valueColumns)
                    .map(c -> c + " = excluded." + c)
                    .collect(Collectors.joining(", ")));
        } else {
            throw new UnsupportedOperationException("IDK how to upsert " + dialect);
        }

        return cachedSql = sql.toString();
    }

    /** @return A new builder instance. */
    public static Builder builder() {
        return new Builder();
    }

    /** @param table The table name to initialize the builder with. */
    public static Builder builder(@NotNull String table) {
        return new Builder().table(table);
    }

    /**
     * Fluent builder for creating {@link UpsertStatement} instances.
     */
    public static final class Builder {
        private String table;
        private String[] keyColumns;
        private String[] valueColumns;

        public Builder table(@NotNull String table) {
            this.table = table;
            return this;
        }

        /** @param keyColumns Columns identifying a unique row. */
        public Builder keys(@Nullable String @NotNull... keyColumns) {
            this.keyColumns = keyColumns;
            return this;
        }

        /** @param valueColumns Additional, non-key, columns to update on conflict. */
        public Builder values(@Nullable String @NotNull... valueColumns) {
            this.valueColumns = valueColumns;
            return this;
        }

        /** @return A new UpsertStatement based on builder configuration. */
        public UpsertStatement build() {
            if (table == null) throw new IllegalStateException("Table name must be set");
            if (keyColumns == null) keyColumns = new String[0];
            if (valueColumns == null) valueColumns = new String[0];
            return new UpsertStatement(table, keyColumns, valueColumns);
        }
    }
}