package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.ExecutionContext;
import io.github.ensgijs.dbm.sql.SqlClient;
import io.github.ensgijs.dbm.sql.SqlDialect;
import io.github.ensgijs.dbm.repository.Repository;
import io.github.ensgijs.dbm.repository.RepositoryApi;
import io.github.ensgijs.dbm.util.function.ThrowingFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorTest {
    @RepositoryApi("base")
    interface BaseRepo extends Repository {}

    /** No @RepositoryApi annotation — runMigrationsFor should throw. */
    interface MissingAnnotRepo extends Repository {}

    @RepositoryApi
    interface EmptyAnnotRepo extends Repository {}

    private SchemaMigrator migrator;
    private SqlClient mockClient;

    @BeforeEach
    protected void setUp() throws Exception {
        mockClient = Mockito.mock(SqlClient.class);
        migrator = new SchemaMigrator(mockClient);
        migrator.refreshVersionCache();
    }

    @Test
    @DisplayName("runMigrationsFor should throw when @RepositoryApi is missing")
    void testMissingAnnotationThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> migrator.runMigrationsFor(MissingAnnotRepo.class),
                "runMigrationsFor must require @RepositoryApi");
    }

    @Test
    @DisplayName("runMigrationsFor with empty @RepositoryApi runs no migrations")
    void testEmptyAnnotationRunsNothing() {
        // No exception; just calls migrate() with no names
        assertDoesNotThrow(() -> migrator.runMigrationsFor(EmptyAnnotRepo.class));
    }

    @Nested
    @DisplayName("migrate(name) Tests")
    class MigrateTests {
        private SqlClient mockClient;
        private ExecutionContext mockContext;
        private SchemaMigrator.MigrationProvider mockProvider;
        private SchemaMigrator migrator;

        @BeforeEach
        void setUp() {
            mockClient = mock(SqlClient.class);
            mockContext = mock(ExecutionContext.class);
            mockProvider = mock(SchemaMigrator.MigrationProvider.class);

            when(mockContext.activeDialect()).thenReturn(SqlDialect.SQLITE);
            when(mockClient.activeDialect()).thenReturn(SqlDialect.SQLITE);
            when(mockClient.executeTransaction(any(ThrowingFunction.class))).thenAnswer(call -> {
                @SuppressWarnings("unchecked")
                ThrowingFunction<ExecutionContext, Object> fn = call.getArgument(0);
                return fn.apply(mockContext);
            });

            migrator = new SchemaMigrator(mockClient, mockProvider);
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
            when(mockProvider.getMigrations(SqlDialect.SQLITE, name)).thenReturn(List.of(migration));

            migrator.migrate(name);

            verify(mockContext).executeUpdate("CREATE TABLE users (id INT)");
            verify(mockContext).executeUpdate(
                    contains("INSERT INTO SchemaMigrations"), eq(name), eq(1L), anyLong());
            verify(mockContext).commit();
        }

        @Test
        void testMigrateSkipsAlreadyApplied() throws SQLException {
            ResultSet mockRs = mock(ResultSet.class);
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getString(1)).thenReturn("users");
            when(mockRs.getLong(2)).thenReturn(1L);

            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                ThrowingFunction<ResultSet, Object> handler = inv.getArgument(1);
                return handler.apply(mockRs);
            }).when(mockClient).executeQuery(contains("SELECT name, version FROM"), any());

            migrator.refreshVersionCache();

            Migration migration = new Migration(
                    "users", 1L,
                    new Migration.SqlSource(SqlDialect.SQLITE, List.of("DROP TABLE users")),
                    Collections.emptySet(),
                    "TestPlugin"
            );
            when(mockProvider.getMigrations(SqlDialect.SQLITE, "users")).thenReturn(List.of(migration));

            migrator.migrate("users");

            verify(mockContext, never()).executeUpdate("DROP TABLE users");
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

            migrator.migrate("java_test_area");

            assertTrue(FakeProgrammaticMigration.wasCalled);
            assertEquals(mockContext, FakeProgrammaticMigration.capturedContext);
            verify(mockContext).executeUpdate(
                    eq("INSERT INTO SchemaMigrations (name, version, applied_at) VALUES (?, ?, ?)"),
                    eq("java_test_area"), eq(101L), anyLong());
            verify(mockContext).commit();
        }
    }
}
