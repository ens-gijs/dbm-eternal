package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.ExecutionContext;
import io.github.ensgijs.dbm.sql.SqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Represents a single, generally, atomic change to the database schema.
 * <p>
 * Migrations are identified by a {@link Key} and can be either SQL-based (loaded from files)
 * or programmatic (Java-based). They support a dependency-aware execution model where migrations
 * can specify other migrations that must be applied before them using the {@code !AFTER} directive.
 * </p>
 *
 * @param key          The unique identifier for this migration (Name + Version + Dialect).
 * @param source       The actual logic to execute; either raw SQL statements or a Java class.
 * @param providedBy   The name of the plugin that registered this migration (for debugging/logging).
 * @param dependencies A set of keys representing migrations that MUST be applied successfully
 * before this one can run.
 */
public record Migration (
        Key key,
        MigrationSource source,
        Set<Key> dependencies,
        String providedBy
) {
    // private static final PrefixedLogger logger = new PrefixedLogger(Migration.class);

    public enum MigrationSourceType { RUN, SQLITE, MYSQL }

    /**
     * The unique identity of a migration.
     * <p>
     * Two migrations are considered the same if they share the same name, version, and dialect.
     * Versioning is typically handled via Unix timestamps (long) to allow for distributed
     * development without version collisions.
     * </p>
     * @param name The functional area (e.g., "users", "inventory")
     * @param version The incremental version for that name
     */
    public record Key(@NotNull String name, long version) implements Comparable<Key> {

        @Override
        public @NotNull String toString() { return name + "." + version; }

        @Override
        public int compareTo(@NotNull Migration.Key that) {
            int k = this.name.compareTo(that.name);
            if (k != 0) return k;
            return Long.compare(this.version, that.version);
        }
    }

    public Migration(
            String name,
            long version,
            MigrationSource source,
            Set<Key> dependencies,
            String providedBy
    ) {
        this(new Key(name, version), source, dependencies, providedBy);
    }

    public String name() {
        return key.name;
    }

    public long version() {
        return key.version;
    }

    public MigrationSourceType sourceType() {
        return source.sourceType();
    }

    /**
     * Represents the actual work to be done.
     * Can be a List of SQL strings or a Java Class reference.
     */
    public sealed interface MigrationSource permits SqlSource, JavaSource {
        default MigrationSourceType sourceType() {
            if (this instanceof SqlSource sql) {
                return switch (sql.dialect()) {
                    case SqlDialect.MYSQL -> MigrationSourceType.MYSQL;
                    case SqlDialect.SQLITE -> MigrationSourceType.SQLITE;
                    default -> throw new IllegalStateException();
                };
            } else {
                return MigrationSourceType.RUN;
            }
        }
    }

    @Override
    public @NotNull String toString() {
        return  key + "{" +
                "sourceType=" + sourceType() +
                ", providedBy='" + providedBy + '\'' +
                ", dependencies=" + dependencies +
                '}';
    }

    /**
     * Contains a list of pre-split SQL statements to be executed in sequence.
     */
    public record SqlSource(SqlDialect dialect, List<String> statements) implements MigrationSource {}

    /**
     * References a Java class that implements {@link ProgrammaticMigration}.
     * Useful for complex data transformations that are difficult to express in pure SQL.
     */
    public record JavaSource(Class<? extends ProgrammaticMigration> migrationClass) implements MigrationSource {}

    /**
     * Interface for complex migrations that require Java logic.
     * <p>Classes implementing this MUST provide a public no-args constructor.
     * They are instantiated via reflection by the {@link SchemaMigrator} at runtime.</p>
     * <p>Note: migrate will be called from within a transaction, if migrate will be performing
     * significant data manipulation consider commiting before starting, otherwise please do not commit.</p>
     */
    public interface ProgrammaticMigration {
        /**
         * Executes the migration logic, called from within a managed transaction.
         * @param ctx The execution context providing the active connection.
         * @throws Exception If any error occurs; will trigger a transaction rollback to the last commit.
         */
        void migrate(ExecutionContext ctx) throws Exception;
    }

    /**
     * Sorts a collection of migrations into an executable order based on their dependencies.
     * <p>
     * This uses a Topological Sort (Kahn's Algorithm). If a migration {@code B.5} is
     * marked as {@code !AFTER: A.2}, then {@code A.2} will always appear earlier in
     * the returned list than {@code B.5}.
     * </p>
     *
     * @param migrations The unsorted collection of migrations.
     * @return A list of migrations sorted such that all dependencies are satisfied
     * by earlier elements in the list.
     * @throws DatabaseException If a circular dependency is detected or if a
     * required dependency is missing from the input set.
     */
    public static List<Migration> sort(Collection<Migration> migrations) throws DatabaseException {
        // TODO: consider making sorting less strict so that a required dependency version follows price-is-right model
        Map<Key, Migration> lookup = new HashMap<>(migrations.size());
        Map<Key, Set<Key>> adjacencyList = new HashMap<>();
        Map<Key, Integer> inDegree = new HashMap<>();

        // Initialize structures
        for (Migration m : migrations) {
            Key key = m.key;
            lookup.put(key, m);
            inDegree.putIfAbsent(key, 0);
            for (Key dep : m.dependencies()) {
                adjacencyList.computeIfAbsent(dep, k -> new HashSet<>()).add(key);
                inDegree.put(key, inDegree.getOrDefault(key, 0) + 1);
            }
        }

        // We use a PriorityQueue to ensure that among migrations with 0 in-degree,
        // the one with the earliest timestamp (version) goes first.
        PriorityQueue<Migration> queue = new PriorityQueue<>(Comparator.comparingLong(Migration::version));

        for (Migration m : migrations) {
            if (inDegree.get(m.key) == 0) {
                queue.add(m);
            }
        }

        List<Migration> sorted = new ArrayList<>(migrations.size());
        while (!queue.isEmpty()) {
            Migration current = queue.poll();
            sorted.add(current);

            Set<Key> dependents = adjacencyList.get(current.key);
            if (dependents != null) {
                for (Key dependentKey : dependents) {
                    int remaining = inDegree.get(dependentKey) - 1;
                    inDegree.put(dependentKey, remaining);
                    if (remaining == 0) {
                        // If the dependency exists in our current load-set, add it
                        if (lookup.containsKey(dependentKey)) {
                            queue.add(lookup.get(dependentKey));
                        }
                    }
                }
            }
        }

        if (sorted.size() < lookup.size()) {
            Map<Key, Migration> missingOrCycled = new HashMap<>(lookup);
            sorted.forEach(m -> missingOrCycled.remove(m.key));
            // TODO: improve error message to be more specific and more informative.
            throw new DatabaseException("Migration sort failed. Possible circular dependency or missing requirement among: " + missingOrCycled.values());
        }

        return sorted;
    }
}
