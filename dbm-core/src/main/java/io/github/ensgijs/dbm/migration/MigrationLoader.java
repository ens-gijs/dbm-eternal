package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.sql.SqlStatementSplitter;
import io.github.ensgijs.dbm.util.io.ResourceScanner;
import io.github.ensgijs.dbm.util.objects.ValueOrException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers, parses, and caches migration files from classpath resources.
 * <p>
 * Migration files must be placed in the {@code db/migrate/} classpath directory and follow
 * the naming convention:
 * <pre>
 *   {name}.{version}[.{dialect}].{ext}
 * </pre>
 * where:
 * <ul>
 * <li>{@code name} — the migration area (e.g., {@code core}, {@code users})</li>
 * <li>{@code version} — a numeric version, typically a Unix timestamp.
 *     Underscores and dashes are stripped before parsing, so {@code 2024_01_01} and
 *     {@code 20240101} are equivalent.</li>
 * <li>{@code dialect} — optional; one of {@code mysql} or {@code sqlite}.
 *     Required for {@code .sql} files; must be absent for {@code .run} files.</li>
 * <li>{@code ext} — {@code sql} for raw SQL migrations or {@code run} for programmatic migrations.</li>
 * </ul>
 * <p>
 * Dependency directives ({@code !AFTER}) may be placed at the top of any migration file
 * before the first statement:
 * <pre>
 *   -- !AFTER: core.20240101
 * </pre>
 * <p>
 * Results are cached per plugin name. The global master migration list is updated with each
 * {@link #loadMigrations} call, making all loaded migrations available to
 * {@link #getMigrations}.
 * </p>
 */
public final class MigrationLoader {
    private final static Logger logger = Logger.getLogger("MigrationLoader");
    private static final String DB_MIGRATE_RESOURCE_PATH = "db/migrate/";

    /**
     * examples: core.2024-01-01_1200.mysql.sql, core.202401011201.run
     * <br/>1: migration name
     * <br/>2: migration version
     * <br/>3: optional sql dialect
     * <br/>4: file extension (one-of: sql, run)
     */
    private static final Pattern FILE_PATTERN = Pattern.compile("^(.+)\\.([0-9_-]+)(?:\\.(" +
            Arrays.stream(SqlDialect.values())
                    .filter(d -> d != SqlDialect.UNDEFINED)
                    .map(d -> d.name().toLowerCase())
                    .collect(Collectors.joining("|"))
            + "))?\\.(sql|run)$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches dependency directives.
     * <br>Format: {@code -- !AFTER: plugin_name.version}
     * <br>Version may contain dashes and underscored for readability. However, the version is treated as an int64.
     * <br>Examples:
     * <pre>
     * -- !AFTER: core.1_700_000_000
     * #!AFTER:economy.2023-10-25
     * // !AFTER permissions.1
     * </pre>
     */
    private static final Pattern AFTER_PATTERN = Pattern.compile(
            "^(?:--|#|//)\\s*!\\s*AFTER(?:\\s*:\\s*|\\s+)([\\w_.-]+)[.]([0-9_-]+)$", Pattern.CASE_INSENSITIVE);

    ///  Map of Plugin Name -> Migrations
    private static final Map<String, ValueOrException<List<Migration>, MigrationParseException>> cache = new HashMap<>();

    private static final Map<SqlDialect, Map<Migration.Key, Migration>> masterMigrationList = Map.of(
            SqlDialect.MYSQL, new ConcurrentHashMap<>(),
            SqlDialect.SQLITE, new ConcurrentHashMap<>());

    private MigrationLoader() {}

    @VisibleForTesting
    static void resetInternalState() {
        cache.clear();
        masterMigrationList.values().forEach(Map::clear);
    }

    /**
     * Returns all migrations needed to bring the named migration area up to date for the given dialect,
     * including any transitive dependencies, topologically sorted and ready to execute.
     * <p>
     * All plugins must have already had their migrations loaded via
     * {@link #loadMigrations(PlatformHandle, ClassLoader)} before this method is called.
     * </p>
     *
     * @param dialect       The SQL dialect to retrieve migrations for.
     * @param migrationName The migration area name (e.g., {@code "users"}, {@code "core"}).
     * @return An ordered list of migrations with all dependencies satisfied.
     * @throws DatabaseException If a circular dependency or missing dependency is detected during sorting.
     */
    public static @NotNull List<Migration> getMigrations(final @NotNull SqlDialect dialect, final @NotNull String migrationName) throws DatabaseException {
        Collection<Migration> allMigrations = masterMigrationList.get(dialect).values();
        Map<Migration.Key, Migration> targetMigrations = new HashMap<>();
        collectDependencies(migrationName, allMigrations, targetMigrations);
        return Migration.sort(targetMigrations.values());
    }

    private static void collectDependencies(final String migrationName, final Collection<Migration> allMigrations, final Map<Migration.Key, Migration> targetMigrations) {
        allMigrations.stream().filter(m -> m.name().equals(migrationName)).forEach(m -> {
            if (targetMigrations.putIfAbsent(m.key(), m) == null) {
                for (Migration.Key dep : m.dependencies()) {
                    collectDependencies(dep.name(), allMigrations, targetMigrations);
                }
            }
        });
    }

    /// @return an unmodifiable, unordered, list of migrations. Results are cached to optimize for repeated calls.
    public static @NotNull List<Migration> loadMigrations(@NotNull PlatformHandle platformHandle, final @NotNull ClassLoader classLoader) throws MigrationParseException {
        final String handleName = platformHandle.name();
        if (cache.containsKey(handleName)) {
            return cache.get(handleName).getOrThrow(MigrationParseException.class);
        }

        List<Migration> discovered = new ArrayList<>();

        try {
            ResourceScanner.visit(classLoader, DB_MIGRATE_RESOURCE_PATH, entry -> {
                ParsedMigrationFileName parsed = ParsedMigrationFileName.of(entry.path());
                if (parsed == null) return;
                try (InputStream is = entry.asStream()) {
                    Migration migration = parseMigration(handleName, classLoader, parsed, is);
                    discovered.add(migration);
                    if (migration.sourceType() == Migration.MigrationSourceType.RUN) {
                        putToMasterList(handleName, entry.path(), SqlDialect.MYSQL, migration);
                        putToMasterList(handleName, entry.path(), SqlDialect.SQLITE, migration);
                    } else if (migration.sourceType() == Migration.MigrationSourceType.MYSQL) {
                        putToMasterList(handleName, entry.path(), SqlDialect.MYSQL, migration);
                    } else if (migration.sourceType() == Migration.MigrationSourceType.SQLITE) {
                        putToMasterList(handleName, entry.path(), SqlDialect.SQLITE, migration);
                    } else {
                        throw new UnsupportedOperationException("unsupported source type: " + migration.sourceType());
                    }
                }
            });

            List<Migration> result = discovered.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(discovered);
            cache.put(handleName, ValueOrException.forValue(result));
            return result;
        } catch (Exception ex) {
            MigrationParseException mpe;
            if (ex instanceof MigrationParseException mx) {
                mpe = mx;
            } else if (ex.getCause() instanceof MigrationParseException mx) {
                mpe = mx;
            } else {
                mpe = new MigrationParseException("Failed to load migrations for: " + handleName, ex);
            }
            cache.put(handleName, ValueOrException.forException(mpe));
            throw mpe;
        }
    }

    private static void putToMasterList(String pluginName, String relativeFileName, SqlDialect sqlDialect, Migration migration) {
        var dialectMasterList = masterMigrationList.get(sqlDialect);
        if (!dialectMasterList.containsKey(migration.key())) {
            dialectMasterList.put(migration.key(), migration);
        } else {
            Migration existing = dialectMasterList.get(migration.key());
            if (!migration.source().equals(existing.source()) || !migration.dependencies().equals(existing.dependencies())) {
                logger.severe(String.format("%s tried to mask '%s' already provided by %s", pluginName, relativeFileName, existing.providedBy()));
            } else {
                logger.warning(String.format("%s contains a duplicate of '%s' already provided by %s", pluginName, relativeFileName, existing.providedBy()));
            }
        }
    }

    /**
     * @param fileName         example: {@code core.42.mysql.sql}
     * @param migrationName    example: {@code core}
     * @param migrationVersion example: {@code 42}
     * @param migrationSourceType {@link Migration.MigrationSourceType}
     */
    @VisibleForTesting
    record ParsedMigrationFileName (
            @NotNull String fileName,
            @NotNull String migrationName,
            long migrationVersion,
            @NotNull Migration.MigrationSourceType migrationSourceType
    ) {
        /**
         *
         * @param relativeFilePath example: {@code db/migrate/core.42.mysql.sql}
         * @throws MigrationParseException upon incorrect dialect usage
         */
        public static @Nullable ParsedMigrationFileName of(@NotNull String relativeFilePath) throws MigrationParseException {
            String fileName = relativeFilePath.substring(Math.max(relativeFilePath.lastIndexOf('/'), relativeFilePath.lastIndexOf('\\')) + 1);
            Matcher matcher = FILE_PATTERN.matcher(fileName);
            if (matcher.matches()) {
                final String name = matcher.group(1);
                ///  one-of: sql, run
                final String fileType = matcher.group(4);
                String compactedVersionStr = matcher.group(2).replaceAll("[_\\-]", "");
                final long version = Long.parseLong(compactedVersionStr);
                Migration.MigrationSourceType mode;
                if (matcher.group(3) != null) {
                    if ("run".equals(fileType)) {
                        throw new MigrationParseException("Migration '.run' files should not specify a dialect in their name.");
                    }
                    SqlDialect dialect = SqlDialect.valueOf(matcher.group(3).toUpperCase(Locale.ENGLISH));
                    mode = switch(dialect) {
                        case UNDEFINED -> throw new MigrationParseException("Unexpected sql dialect " + dialect);
                        case SqlDialect.SQLITE -> Migration.MigrationSourceType.SQLITE;
                        case SqlDialect.MYSQL -> Migration.MigrationSourceType.MYSQL;
                    };
                } else {
                    if ("sql".equals(fileType)) {
                        throw new MigrationParseException("Migration '.sql' files must specify a dialect in their name.");
                    }
                    mode = Migration.MigrationSourceType.RUN;
                }
                return new ParsedMigrationFileName(fileName, name, version, mode);
            }
            return null;
        }

    }

    @VisibleForTesting
    record MigrationFileParseResult(Set<Migration.Key> dependencies, String content) {}

    @VisibleForTesting
    static @NotNull Migration parseMigration(
            @NotNull String pluginName,
            @NotNull ClassLoader classLoader,
            @NotNull ParsedMigrationFileName parsedMigrationFileName,
            @NotNull InputStream is
    ) throws MigrationParseException {
        try {
            MigrationFileParseResult parseResult = parseMigrationContent(parsedMigrationFileName, is);

            Migration.MigrationSource source;
            if (parsedMigrationFileName.migrationSourceType == Migration.MigrationSourceType.RUN) {
                // already guaranteed that parseResult.content is a single non-empty line.
                Class<?> clazz = Class.forName(parseResult.content, true, classLoader);
                source = new Migration.JavaSource(clazz.asSubclass(Migration.ProgrammaticMigration.class));
            } else {
                SqlDialect dialect = switch (parsedMigrationFileName.migrationSourceType) {
                    case RUN -> null;  // unreachable, but required
                    case Migration.MigrationSourceType.MYSQL -> SqlDialect.MYSQL;
                    case Migration.MigrationSourceType.SQLITE -> SqlDialect.SQLITE;
                };
                source = new Migration.SqlSource(
                        dialect,
                        SqlStatementSplitter.splitStatement(parseResult.content));
            }

            return new Migration(
                    parsedMigrationFileName.migrationName,
                    parsedMigrationFileName.migrationVersion,
                    source,
                    parseResult.dependencies,
                    pluginName);
        } catch (MigrationParseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MigrationParseException("Error while parsing migration file: " + parsedMigrationFileName.fileName, ex);
        }
    }

    /**
     * Extracted logic to allow testing a specific stream without JAR overhead.
     * @throws MigrationParseException upon validation failure that .run files contain exactly one line of payload outside of comments / pragmas.
     * @throws IOException upon stream read error.
     */
    @VisibleForTesting
    static @NotNull MigrationFileParseResult parseMigrationContent(
            final ParsedMigrationFileName parsedMigrationFileName,
            final InputStream is
    ) throws MigrationParseException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        Set<Migration.Key> deps = new HashSet<>();
        StringBuilder content = new StringBuilder();
        boolean acceptingPragmas = true;

        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher afterMatch = AFTER_PATTERN.matcher(trimmed);
            if (acceptingPragmas) {
                // Parse Directives
                if (afterMatch.find()) {
                    deps.add(new Migration.Key(afterMatch.group(1), Long.parseLong(afterMatch.group(2).replaceAll("[-_]", ""))));
                    continue;
                }
                // Skip other comments
                if (trimmed.startsWith("--") || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                acceptingPragmas = false;
            } else {
                if (afterMatch.find()) {
                    throw new MigrationParseException(
                            "Encountered !AFTER pragma appearing after first statement at line: " + lineNo);
                }
            }
            // Skip comments
            if (trimmed.startsWith("--") || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            content.append(line).append("\n");
        }

        final String contentStr = content.toString().trim();
        if (contentStr.isBlank())
            throw new MigrationParseException("Migration file contained no statements.");
        var result = new MigrationFileParseResult(deps, contentStr);

        if (parsedMigrationFileName.migrationSourceType == Migration.MigrationSourceType.RUN) {
            List<String> lines = result.content.lines().toList();
            if (lines.size() != 1) {
                throw new MigrationParseException(
                        "Migration '.run' files must contain exactly one non-empty, non-comment, line containing a fully qualified class name.");
            }
        }
        return result;
    }
}
