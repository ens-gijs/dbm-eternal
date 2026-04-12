/**
 * Schema migration engine for versioned, dependency-aware database evolution.
 *
 * <h2>Overview</h2>
 * <p>
 * Migrations are discovered from classpath resources under {@code db/migrate/} and parsed
 * by {@link io.github.ensgijs.dbm.migration.MigrationLoader}. Each migration is represented
 * by a {@link io.github.ensgijs.dbm.migration.Migration} record identified by a
 * {@link io.github.ensgijs.dbm.migration.Migration.Key} (name + version).
 * </p>
 *
 * <h2>File Naming</h2>
 * <pre>
 *   {name}.{version}[.{dialect}].{ext}
 * </pre>
 * <ul>
 * <li>{@code name} — migration area, e.g. {@code users} or {@code core}</li>
 * <li>{@code version} — numeric (typically Unix timestamp); underscores and dashes are stripped</li>
 * <li>{@code dialect} — {@code mysql} or {@code sqlite} (required for {@code .sql} files)</li>
 * <li>{@code ext} — {@code sql} for raw SQL; {@code run} for programmatic Java migrations</li>
 * </ul>
 *
 * <h2>Dependency Directives</h2>
 * <p>
 * Place {@code !AFTER} directives at the top of a migration file (before any SQL) to declare
 * ordering constraints. {@link io.github.ensgijs.dbm.migration.Migration#sort} uses Kahn's
 * topological sort algorithm to produce a valid execution order.
 * </p>
 * <pre>
 *   -- !AFTER: core.1700000000
 * </pre>
 *
 * <h2>Programmatic Migrations</h2>
 * <p>
 * A {@code .run} file contains a single fully-qualified class name implementing
 * {@link io.github.ensgijs.dbm.migration.Migration.ProgrammaticMigration}. The class must
 * have a public no-args constructor and is instantiated via reflection at runtime.
 * </p>
 *
 * <h2>Execution</h2>
 * <p>
 * {@link io.github.ensgijs.dbm.migration.SchemaMigrator} applies pending migrations in sorted
 * order, each within its own transaction, and records completions in the
 * {@code SchemaMigrations} table.
 * </p>
 */
package io.github.ensgijs.dbm.migration;
