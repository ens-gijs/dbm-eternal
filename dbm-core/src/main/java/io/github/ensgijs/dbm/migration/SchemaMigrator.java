package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.ExecutionContext;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.sql.SqlStatementSplitter;
import io.github.ensgijs.dbm.repository.Repository;
import io.github.ensgijs.dbm.repository.RepositoryApi;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Orchestrates the database schema evolution process by applying versioned migrations.
 * <p>
 * This class ensures that all required tables and structures exist before any repository
 * operations occur. It maintains a persistent history in the {@code SchemaMigrations} table
 * to prevent re-applying the same changes.
 * </p>
 * Features include:
 * <ul>
 * <li><b>Atomic Migrations:</b> Each migration is executed within its own transaction.</li>
 * <li><b>Dependency Resolution:</b> Supports dependencies via the {@code !AFTER} directive.</li>
 * <li><b>Hybrid Support:</b> Handles both raw SQL and programmatic (Java-based) migrations.</li>
 * </ul>
 */
public final class SchemaMigrator {
    private final static Logger logger = Logger.getLogger("SchemaMigrator");

    private final SqlDatabaseManager manager;
    /// Cache: Name -> Set of applied version timestamps
    private final Map<String, Set<Long>> appliedCache = new ConcurrentHashMap<>();
    private final MigrationProvider migrationProvider;

    /// Functional interface to decouple static MigrationLoader
    @FunctionalInterface
    public interface MigrationProvider {
        List<Migration> getMigrations(SqlDialect dialect, String name);
    }

    /**
     * Construction of this object may occur before {@link SqlDatabaseManager} is fully
     * initialized. To support this case, {@link #refreshVersionCache()} must be called
     * manually only after {@link SqlDatabaseManager} is able to provide a connection.
     *
     * @param manager The manager providing database connectivity and dialect information.
     * @throws DatabaseException If the `SchemaMigrations` table cannot be verified or the version cache fail to load.
     */
    public SchemaMigrator(SqlDatabaseManager manager) {
        // Default constructor uses the real loader
        this(manager, MigrationLoader::getMigrations);
    }

    @VisibleForTesting
    SchemaMigrator(SqlDatabaseManager manager, MigrationProvider provider) {
        this.manager = manager;
        this.migrationProvider = provider;
    }

    /**
     * Verifies existence of the {@code SchemaMigrations} table, creating it if necessary.
     * This table serves as the "Source of Truth" for the current state of the database.
     * <p>The table structure is adapted based on whether the active dialect is MySQL or SQLite.</p>
     * @throws DatabaseException If the DDL execution fails.
     */
    private void ensureHistoryTable() throws DatabaseException {
        // We use LONG/BIGINT for version to support timestamps
        String sql = manager.activeDialect() == SqlDialect.MYSQL
                ? """
                  CREATE TABLE IF NOT EXISTS SchemaMigrations (
                      name VARCHAR(128),
                      version BIGINT,
                      applied_at BIGINT,
                      PRIMARY KEY (name, version)
                  )"""
                : """
                  CREATE TABLE IF NOT EXISTS SchemaMigrations (
                      name TEXT,
                      version INTEGER,
                      applied_at INTEGER,
                      PRIMARY KEY (name, version)
                  )""";
        manager.executeUpdate(sql);
    }

    /**
     * Ensures the {@code SchemaMigrations} table exists and populates the {@link #appliedCache} from it.
     * This is called during initialization and upon a {@link SqlDatabaseManager} config reload which
     * has invalidated the existing connection.
     * @throws DatabaseException If the SELECT query fails.
     */
    public void refreshVersionCache() throws DatabaseException {
        long start = System.currentTimeMillis();
        appliedCache.clear();
        ensureHistoryTable();
        manager.executeQuery("SELECT name, version FROM SchemaMigrations", rs -> {
            if (rs != null) {  // may be null in test environment when using mocks
                while (rs.next()) {
                    appliedCache.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getLong(2));
                }
            }
            return null;
        });
        logger.info("Version cache refreshed in " + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Checks for and applies migrations declared on the repository interface.
     * @param repoInterface The interface to inspect for {@link RepositoryApi}.
     * @throws IllegalArgumentException If the annotation is missing.
     */
    public <I extends Repository> void runMigrationsFor(Class<I> repoInterface) {
        RepositoryApi annot = repoInterface.getAnnotation(RepositoryApi.class);

        if (annot == null) {
            throw new IllegalArgumentException(
                    "Repository interface " + repoInterface.getName() + " must be annotated with @RepositoryApi.");
        }

        Set<String> allNames = new LinkedHashSet<>();
        collectMigrationNames(repoInterface, allNames);

        // Execute in order of most distant ancestor first.
        for (String name :  allNames.stream().toList().reversed()) {
            migrate(name);
        }
    }

    /**
     * Helper to recursively collect migration names from the hierarchy.
     */
    @VisibleForTesting
    void collectMigrationNames(Class<?> clazz, Set<String> collected) {
        RepositoryApi annot = clazz.getAnnotation(RepositoryApi.class);
        if (annot != null) {
            Arrays.stream(annot.value())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(() -> collected, Set::add, Set::addAll);
        }

        if (annot == null || annot.inheritMigrations()) {
            for (Class<?> parent : clazz.getInterfaces()) {
                collectMigrationNames(parent, collected);
            }
        }
    }

    /**
     * Checks for and applies any new migrations found in the classpath for a specific functional area.<br/>
     * This method will:
     * <ol>
     * <li> Load migrations associated with the {@code targetName}.
     * <li> Filter out migrations already recorded in {@link #appliedCache}.
     * <li> Sort migrations based on their internal version and declared dependencies.
     * <li> Apply them sequentially.
     * </ol>
     *
     * @param targetName The name of the functional area to migrate (e.g., "core", "economy").
     * @throws DatabaseException If a migration fails or a dependency is missing/cyclic.
     */
    public void migrate(String targetName) throws DatabaseException {
        var requiredMigrations = migrationProvider.getMigrations(manager.activeDialect(), targetName).stream()
                .filter(this::isPending).toList();
        if (!requiredMigrations.isEmpty()) {
            manager.executeTransaction(ctx -> {
                requiredMigrations.forEach(m -> executeMigration(ctx, m));
                return null;
            });
        }
    }

    private boolean isPending(Migration m) {
        Set<Long> versions = appliedCache.get(m.name());
        return versions == null || !versions.contains(m.version());
    }

    /**
     * Executes a single migration and logs the result.
     * <p>
     * If the migration is SQL-based, it uses the {@link SqlStatementSplitter} to split
     * and run individual statements. If Java-based, it instantiates the class and runs its
     * {@code migrate} method.
     * </p>
     * <p>
     * <b>Atomicity:</b> The migration logic and the insertion into the history table
     * are bundled into a single transaction.
     * </p>
     *
     * @param migration The migration record to apply.
     * @throws DatabaseException If the migration logic throws an exception or the transaction fails.
     */
    private void executeMigration(ExecutionContext ctx, Migration migration) throws DatabaseException {
        logger.info("Applying migration: " + migration.key());

        try {
            if (migration.source() instanceof Migration.SqlSource sqlMigration) {
                if (sqlMigration.dialect() != ctx.activeDialect())
                    throw new IllegalArgumentException(
                            "Attempted to run migration " + migration.key() + " using the wrong dialect!");
                // Execute each split statement
                for (String statement : sqlMigration.statements()) {
                    ctx.executeUpdate(statement);
                }
            } else if (migration.source() instanceof Migration.JavaSource javaSource) {
                // Instantiate and run programmatic migration
                Migration.ProgrammaticMigration instance = javaSource.migrationClass()
                        .getDeclaredConstructor()
                        .newInstance();
                instance.migrate(ctx);
            }

            // Record success
            ctx.executeUpdate(
                    "INSERT INTO SchemaMigrations (name, version, applied_at) VALUES (?, ?, ?)",
                    migration.name(), migration.version(), System.currentTimeMillis()
            );
            ctx.commit();
            // Update local cache after successful transaction commit
            appliedCache.computeIfAbsent(migration.name(), k -> new HashSet<>()).add(migration.version());
        } catch (Exception ex) {
            throw new DatabaseException("Failed to apply migration " + migration.key(), ex);
        }
    }
}