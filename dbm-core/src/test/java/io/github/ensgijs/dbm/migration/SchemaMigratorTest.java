package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.ExecutionContext;
import io.github.ensgijs.dbm.sql.SqlDatabaseManager;
import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.repository.Repository;
import io.github.ensgijs.dbm.repository.RepositoryApi;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorTest {
    // Scenario 1: Basic
    @RepositoryApi("base")
    interface BaseRepo extends Repository {}

    // Scenario 2: Inheritance disabled (default)
    @RepositoryApi(value = "extension", inheritMigrations = false)
    interface NoInheritRepo extends BaseRepo {}

    // Scenario 3: Inheritance enabled
    @RepositoryApi(value = "extension", inheritMigrations = true)
    interface InheritRepo extends BaseRepo {}

    // Scenario 4: Deep inheritance
    @RepositoryApi(value = "deep", inheritMigrations = true)
    interface DeepInheritRepo extends InheritRepo {}

    // Scenario 5: Duplicate names
    @RepositoryApi(value = {"base", "duplo"}, inheritMigrations = true)
    interface DuplicateRepo extends BaseRepo {}

    // Scenario 6: Missing annotation
    interface MissingAnnotRepo extends BaseRepo {}

    // Scenario 7: Missing annotation in inheritance hierarchy is OK
    @RepositoryApi(value = "mask", inheritMigrations = true)
    interface MaskingMissingAnnotRepo extends MissingAnnotRepo {}

    // Scenario: empty is OK (inherit=true with a base)
    @RepositoryApi()
    interface EmptyAnnotRepo extends MissingAnnotRepo {}

    // Scenario: empty is OK (inherit=false with a base)
    @RepositoryApi(inheritMigrations = false)
    interface EmptyAnnotNoInheritRepo extends MissingAnnotRepo {}

    // Scenario: empty is OK (without a base)
    @RepositoryApi()
    interface EmptyAnnotNoBaseRepo extends Repository {}

    private SchemaMigrator migrator;
    private SqlDatabaseManager mockManager;

    @BeforeEach
    protected void setUp() throws Exception {
        mockManager = Mockito.mock(SqlDatabaseManager.class);
        // Assuming refreshVersionCache is called in constructor
        migrator = new SchemaMigrator(mockManager);
        migrator.refreshVersionCache();
    }

    @Test
    @DisplayName("Should collect only local names when inherit is false")
    void testNoInheritance() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(NoInheritRepo.class, names);

        assertEquals(1, names.size());
        assertTrue(names.contains("extension"));
        assertFalse(names.contains("base"), "Should not have collected names from parent interface");
    }

    @Test
    @DisplayName("Should collect names recursively when inherit is true")
    void testWithInheritance() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(InheritRepo.class, names);

        assertEquals(2, names.size());
        assertTrue(names.contains("extension"));
        assertTrue(names.contains("base"));
    }

    @Test
    @DisplayName("Should collect names from multiple levels of inheritance")
    void testDeepInheritance() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(DeepInheritRepo.class, names);

        assertEquals(3, names.size());
        assertIterableEquals(List.of("deep", "extension", "base"), names);
    }

    @Test
    @DisplayName("Should de-duplicate migration names")
    void testDeduplication() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(DuplicateRepo.class, names);

        assertEquals(2, names.size(), "Duplicate names should be filtered out");
        assertTrue(names.contains("base"));
        assertTrue(names.contains("duplo"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when @RepositoryApi is missing")
    void testMissingAnnotationThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            migrator.runMigrationsFor(MissingAnnotRepo.class);
        }, "runMigrationsFor should strictly require the @RepositoryApi annotation");
    }

    @Test
    @DisplayName("Should skip over intermediate interfaces lacking @RepositoryApi")
    void testMaskedMissingAnnotationDoesNotThrow() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(MaskingMissingAnnotRepo.class, names);

        assertEquals(2, names.size());
        assertIterableEquals(List.of("mask", "base"), names);
    }

    @Test
    @DisplayName("Should allow empty @RepositoryApi() - inherit values, with base")
    void testShouldAllowEmptyMigrationName_inheritValues() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(EmptyAnnotRepo.class, names);

        assertEquals(1, names.size());
        assertIterableEquals(List.of("base"), names);
    }

    @Test
    @DisplayName("Should allow empty @RepositoryApi() - no-inherit values, with base")
    void testShouldAllowEmptyMigrationName_noInheritValues() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(EmptyAnnotNoInheritRepo.class, names);

        assertTrue(names.isEmpty());
    }

    @Test
    @DisplayName("Should allow empty @RepositoryApi() - without base")
    void testShouldAllowEmptyMigrationName_noBase() {
        Set<String> names = new LinkedHashSet<>();
        migrator.collectMigrationNames(EmptyAnnotNoBaseRepo.class, names);

        assertTrue(names.isEmpty());
    }

    @Nested
    @DisplayName("migrate(name) Tests")
    class MigrateTests {
        private SqlDatabaseManager mockManager;
        private ExecutionContext mockContext;
        private SchemaMigrator.MigrationProvider mockProvider;
        private SchemaMigrator migrator;

        @BeforeEach
        void setUp() {
            mockManager = mock(SqlDatabaseManager.class);
            mockContext = mock(ExecutionContext.class);
            mockProvider = mock(SchemaMigrator.MigrationProvider.class);

            when(mockContext.activeDialect()).thenReturn(SqlDialect.SQLITE);
            when(mockManager.activeDialect()).thenReturn(SqlDialect.SQLITE);
            when(mockManager.executeTransaction(any(ThrowingFunction.class))).thenAnswer(call -> {
                return ((ThrowingFunction) call.getArgument(0)).apply(mockContext);
            });

            // We assume the constructor calls refreshVersionCache,
            // which we can let fail silently or mock to return an empty set.
            migrator = new SchemaMigrator(mockManager, mockProvider);
        }

        @Test
        void testMigrateExecutesSqlAndRecordsSuccess() throws Exception {
            String name = "users";
            Migration migration = new Migration(
                    name, 1L,
                    new Migration.SqlSource(SqlDialect.SQLITE, List.of("CREATE TABLE users (id INT)")),
                    Collections.emptySet(),
                    "TestPlugin"
            );

            when(mockProvider.getMigrations(SqlDialect.SQLITE, name))
                    .thenReturn(List.of(migration));

            migrator.migrate(name);

            verify(mockContext).executeUpdate("CREATE TABLE users (id INT)");
            verify(mockContext).executeUpdate(
                    contains("INSERT INTO SchemaMigrations"),
                    eq(name), eq(1L), anyLong()
            );
            verify(mockContext).commit();
        }

        @Test
        void testMigrateSkipsAlreadyApplied() throws SQLException {// 1. Prepare Mock ResultSet
            ResultSet mockRs = mock(ResultSet.class);
            // Simulate one existing migration: "users" version 1
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getString(1)).thenReturn("users");
            when(mockRs.getLong(2)).thenReturn(1L);

            // 2. Mock executeQuery to run our handler with the mock ResultSet
            doAnswer(invocation -> {
                ThrowingFunction handler = invocation.getArgument(1);
                return handler.apply(mockRs);
            }).when(mockManager).executeQuery(contains("SELECT name, version FROM"), any());

            // 3. Refresh cache to load the mock data
            migrator.refreshVersionCache();

            // 4. Mock the provider to return the "users.1" migration
            Migration migration = new Migration(
                    "users", 1L,
                    new Migration.SqlSource(SqlDialect.SQLITE, List.of("DROP TABLE users")),
                    Collections.emptySet(),
                    "TestPlugin"
            );
            when(mockProvider.getMigrations(SqlDialect.SQLITE, "users"))
                    .thenReturn(List.of(migration));

            // 5. Run the migration
            migrator.migrate("users");

            // 6. Assert: The SQL for the migration should NEVER have been executed
            verify(mockContext, never()).executeUpdate("DROP TABLE users");

            // Assert: No commit should happen because no migrations were applied
            verify(mockContext, never()).commit();
        }

        @Test
        void testRollbackOnFailure() throws Exception {
            String name = "fail";
            Migration migration = new Migration(
                    name, 2L,
                    new Migration.SqlSource(SqlDialect.SQLITE, List.of("INVALID SQL")),
                    Collections.emptySet(),
                    "TestPlugin"
            );

            when(mockProvider.getMigrations(any(), anyString())).thenReturn(List.of(migration));
            doThrow(new DatabaseException("Syntax Error")).when(mockContext).executeUpdate("INVALID SQL");

            assertThrows(DatabaseException.class, () -> migrator.migrate(name));
            verify(mockContext, never()).commit();
        }

        @Test
        @DisplayName("Should apply Java-based migration and record history")
        void testExecuteMigrationJavaSource() throws Exception {
            Migration migration = new Migration(
                    "java_test_area", 101L,
                    new Migration.JavaSource(FakeProgrammaticMigration.class),
                    Collections.emptySet(),
                    "TestPlugin"
            );

            when(mockProvider.getMigrations(any(), anyString())).thenReturn(List.of(migration));
            // 2. Act: Call executeMigration (assuming visibility is package-private)
            migrator.migrate("java_test_area");

            // 3. Assert: Verify the Java class was called
            assertTrue(FakeProgrammaticMigration.wasCalled,
                    "The ProgrammaticMigration.migrate() method should have been called.");
            assertEquals(mockContext, FakeProgrammaticMigration.capturedContext,
                    "The current ExecutionContext should be passed to the Java migration.");

            // 4. Assert: Verify history table record
            verify(mockContext).executeUpdate(
                    eq("INSERT INTO SchemaMigrations (name, version, applied_at) VALUES (?, ?, ?)"),
                    eq("java_test_area"),
                    eq(101L),
                    anyLong()
            );

            // 5. Assert: Verify transaction commit
            verify(mockContext).commit();
        }
    }
}