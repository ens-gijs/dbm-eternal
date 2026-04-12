package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.platform.PlatformHandle;
import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.migration.MigrationLoader.ParsedMigrationFileName;
import io.github.ensgijs.dbm.migration.MigrationLoader.MigrationFileParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MigrationLoaderTest {
    @Nested
    @DisplayName("ParsedMigrationFileName#of() Tests")
    class ParsedMigrationFileNameTests {
        @Test
        @DisplayName("Should correctly parse a valid MySQL migration filename using forward slashes")
        void testFileNameParsingSql_HappyCase_MySql_ForwardSlashes() throws Exception {
            var parsed = ParsedMigrationFileName.of("db/migrate/core.2024_03_13.mysQl.sql");

            assertNotNull(parsed);
            assertEquals("core", parsed.migrationName());
            assertEquals(20240313L, parsed.migrationVersion());
            assertEquals(Migration.MigrationSourceType.MYSQL, parsed.migrationSourceType());
        }

        @Test
        @DisplayName("Should correctly parse a valid MySQL migration filename using back slashes")
        void testFileNameParsingSql_HappyCase_MySql_BackSlashes() throws Exception {
            var parsed = ParsedMigrationFileName.of("db\\migrate\\core.2024_03_13.mysQl.sql");

            assertNotNull(parsed);
            assertEquals("core", parsed.migrationName());
            assertEquals(20240313L, parsed.migrationVersion());
            assertEquals(Migration.MigrationSourceType.MYSQL, parsed.migrationSourceType());
        }

        @Test
        @DisplayName("Should correctly parse a valid SQLite migration filename")
        void testFileNameParsingSql_HappyCase_SQLite() throws Exception {
            var parsed = ParsedMigrationFileName.of("db/migrate/core.20240313.sqLite.sql");

            assertNotNull(parsed);
            assertEquals("core", parsed.migrationName());
            assertEquals(20240313L, parsed.migrationVersion());
            assertEquals(Migration.MigrationSourceType.SQLITE, parsed.migrationSourceType());
        }

        @Test
        @DisplayName("Should not recognize UNDEFINED dialect in filename")
        void testFileNameParsingSql_UsingDialectUndefined() throws Exception {
            assertNull(ParsedMigrationFileName.of("db/migrate/core.20240313.undefined.sql"));
        }

        @Test
        @DisplayName("Should throw exception if .sql file is missing a dialect")
        void testFileNameParsingSql_MissingDialect() {
            assertThrows(MigrationParseException.class, () ->
                    ParsedMigrationFileName.of("db/migrate/core.100.sql"));
        }

        @Test
        @DisplayName("Should correctly parse a .run filename (dialect-less)")
        void testFileNameParsingRun_HappyCase() throws Exception {
            var parsed = ParsedMigrationFileName.of("db/migrate/cleanup.500.run");

            assertNotNull(parsed);
            assertEquals(Migration.MigrationSourceType.RUN, parsed.migrationSourceType());
        }

        @Test
        @DisplayName("Should throw when a .run filename declares a dialect")
        void testFileNameParsingRun_ContainingDialect() throws Exception {
            assertThrows(MigrationParseException.class, () ->
                    ParsedMigrationFileName.of("db/migrate/cleanup.500.mysql.run"));
            assertThrows(MigrationParseException.class, () ->
                    ParsedMigrationFileName.of("db/migrate/cleanup.500.sqlite.run"));
        }

        @Test
        @DisplayName("Should return null for unknown file extensions")
        void testUnknownFileExtensions() throws MigrationParseException {
            assertNull(ParsedMigrationFileName.of("db/migrate/core.100.mysql.sqlm"));
            assertNull(ParsedMigrationFileName.of("db/migrate/core.100.xrun"));
        }
    }


    @Test
    @DisplayName("Should extract !AFTER dependencies and strip comments")
    void testParseMigrationContent() throws Exception {
        String rawContent = """
                    -- !AFTER: other_plugin.123
                    # !AFTER: core.456
                    -- A regular comment
                    SELECT * FROM users;
                    
                    SELECT * FROM cats;
                    -- SELECT * FROM bats;
                    """;

        var fileName = new ParsedMigrationFileName("test.1.mysql.sql", "test", 1, Migration.MigrationSourceType.MYSQL);
        var result = MigrationLoader.parseMigrationContent(fileName,
                new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8)));

        // Check Dependencies
        assertEquals(2, result.dependencies().size());
        assertTrue(result.dependencies().stream().anyMatch(k -> k.name().equals("other_plugin") && k.version() == 123));

        // Check content (comments and pragmas should be stripped)
        assertEquals("SELECT * FROM users;\nSELECT * FROM cats;", result.content());
    }

    @Test
    @DisplayName("!AFTER, white spacing allowed around ! and colon optional")
    void testAfterPragmaFlexibility() throws Exception {
        String rawContent = """
                    #! after aaa.123
                    # ! AFTER : bbb.456
                    #!AFTER:ccc.789
                    #!AFTERnope.999
                    SELECT * FROM users;""";

        var fileName = new ParsedMigrationFileName("test.1.mysql.sql", "test", 1, Migration.MigrationSourceType.MYSQL);
        var result = MigrationLoader.parseMigrationContent(fileName,
                new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8)));

        // Check Dependencies
        assertEquals(3, result.dependencies().size());
        assertTrue(result.dependencies().stream().anyMatch(k -> k.name().equals("aaa") && k.version() == 123));
        assertTrue(result.dependencies().stream().anyMatch(k -> k.name().equals("bbb") && k.version() == 456));
        assertTrue(result.dependencies().stream().anyMatch(k -> k.name().equals("ccc") && k.version() == 789));
    }

    @Test
    @DisplayName("!AFTER version can contain dashes and underscores")
    void testAfterVersionsCanContainDashesAndUnderscores() throws Exception {
        String rawContent = """
                    # !AFTER: zoo.1-2_3
                    SELECT * FROM users;""";

        var fileName = new ParsedMigrationFileName("test.1.mysql.sql", "test", 1, Migration.MigrationSourceType.MYSQL);
        var result = MigrationLoader.parseMigrationContent(fileName,
                new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8)));

        // Check Dependencies
        assertEquals(1, result.dependencies().size());
        assertTrue(result.dependencies().stream().anyMatch(k -> k.name().equals("zoo") && k.version() == 123));
    }

    @Test
    @DisplayName("Should throw when !AFTER is encountered following first statement")
    void testThrowsWhenAfterPragmaAppearsFollowingFirstStatement() throws Exception {
        String rawContent = """
                    SELECT * FROM users;
                    // !AFTER: other_plugin.123
                    """;

        var fileName = new ParsedMigrationFileName("test.1.mysql.sql", "test", 1, Migration.MigrationSourceType.MYSQL);
        assertThrows(MigrationParseException.class, () ->
                MigrationLoader.parseMigrationContent(fileName,
                        new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("Should throw when migration content is empty")
    void testThrowsWhenMigrationContentIsEmpty() throws Exception {
        String rawContent = """
                    // !AFTER: other_plugin.123
                    // SELECT * FROM users;
                    """;

        var fileName = new ParsedMigrationFileName("test.1.mysql.sql", "test", 1, Migration.MigrationSourceType.MYSQL);
        assertThrows(MigrationParseException.class, () ->
                MigrationLoader.parseMigrationContent(fileName,
                        new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("Should throw if .run file contains more than one line of code")
    void testInvalidRunFileContent() {
        String rawContent = "com.example.Migration\nExtraLine";
        var fileName = new ParsedMigrationFileName("fix.1.run", "fix", 1, Migration.MigrationSourceType.RUN);

        assertThrows(MigrationParseException.class, () ->
                MigrationLoader.parseMigrationContent(fileName,
                        new ByteArrayInputStream(rawContent.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void testParseValidSqlMigration() throws Exception {
        String content = "-- !AFTER: core.100\n-- and cats!\n  -- !AFTER: pets.2\nCREATE TABLE users (id INT);\n-- CATS\nCREATE TABLE cats (id INT);";
        InputStream is = new ByteArrayInputStream(content.getBytes());

        MigrationFileParseResult m = MigrationLoader.parseMigrationContent(
                ParsedMigrationFileName.of("users.2026-03-13.mysql.sql"), is);

        assertNotNull(m);
        assertEquals(2, m.dependencies().size());

        // Verify dependency keys
        var iter = m.dependencies().stream().sorted().iterator();
        Migration.Key dep = iter.next();
        assertEquals("core", dep.name());
        assertEquals(100L, dep.version());

        dep = iter.next();
        assertEquals("pets", dep.name());
        assertEquals(2L, dep.version());
    }

    @Test
    void testParseJavaMigration() throws Exception {
        String content = "io.github.ensgijs.dbm.migration.FakeProgrammaticMigration";
        InputStream is = new ByteArrayInputStream(content.getBytes());

        Migration m = MigrationLoader.parseMigration(
                "MyPlugin",
                this.getClass().getClassLoader(),
                ParsedMigrationFileName.of("data_fix.2024-0202.run"),
                is);

        assertNotNull(m);
        assertInstanceOf(Migration.JavaSource.class, m.source());
        assertSame(FakeProgrammaticMigration.class, ((Migration.JavaSource) m.source()).migrationClass());
        assertEquals(20240202L, m.version());
    }


    @Nested
    @DisplayName("Loaded Resources Tests")
    class LoadedResourcesTests {
        PlatformHandle plugin;

        @BeforeEach
        void setUp() throws MigrationParseException {
            // Clear caches to ensure test isolation
            MigrationLoader.resetInternalState();
            plugin = new SimplePlatformHandle("MockPlugin", null, Collections.emptyList());
            MigrationLoader.loadMigrations(plugin, this.getClass().getClassLoader());
        }


        @Test
        void testLoadMigrations() throws MigrationParseException {
            List<Migration> migrations = MigrationLoader.loadMigrations(plugin, this.getClass().getClassLoader());
            assertNotNull(migrations);
            assertFalse(migrations.isEmpty());
            assertEquals(5, migrations.size());
            Migration m = migrations.get(0);
            assertEquals("MockPlugin", m.providedBy());
            assertEquals("ut-apex", m.name());
            assertEquals(50, m.version());
            assertEquals(1, m.dependencies().size());
            assertEquals(Migration.MigrationSourceType.RUN, m.sourceType());
            assertEquals(
                    new Migration.Key("ut-middle", 10),
                    m.dependencies().stream().findFirst().get());
        }


        @Test
        void testGetMigrations() {
            assertTrue(MigrationLoader.getMigrations(SqlDialect.SQLITE, "ut-zzzzz").isEmpty());
            List<Migration> migrations = MigrationLoader.getMigrations(SqlDialect.SQLITE, "ut-apex");
            assertEquals(2, migrations.size());
            assertEquals("ut-middle.10", migrations.get(0).key().toString());
            assertEquals("ut-apex.50", migrations.get(1).key().toString());

            migrations = MigrationLoader.getMigrations(SqlDialect.MYSQL, "ut-apex");
            assertEquals(4, migrations.size());
            assertEquals("ut-bottom.10", migrations.get(0).key().toString());
            assertEquals("ut-middle.10", migrations.get(1).key().toString());
            assertEquals("ut-middle.20", migrations.get(2).key().toString());
            assertEquals("ut-apex.50", migrations.get(3).key().toString());
        }
    }
}
