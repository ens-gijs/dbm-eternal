package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import io.github.ensgijs.dbm.sql.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test: real {@link RepositoryRegistry} + real {@link SqlDatabaseManager}
 * + in-memory SQLite + real migration execution, no mocks. Exercises the full wiring path that unit won't reach:
 * publication → closeRegistration → run migrations → get() → flyweight → CRUD → dialect-change rejection.
 */
public class RegistryEndToEndTest {

    @RepositoryImpl(dialects = {SqlDialect.SQLITE})
    public static class SqliteOnlyFakeRepositoryImpl extends AbstractRepository implements FakeRepository {
        private final UpsertStatement putUpsert = UpsertStatement.builder("FakeRepo").keys("key").values("value").build();
        public SqliteOnlyFakeRepositoryImpl(SqlClient c) { super(c); }
        @Override public void put(int key, String value) {
            sqlClient.executeUpsert(putUpsert, key, value);
        }
        @Override public String get(int key) {
            return sqlClient.executeQuery("SELECT value FROM FakeRepo WHERE key = ?", rs -> {
                if (rs.next()) return rs.getString("value");
                return null;
            }, key);
        }
        @Override public void clear() {
            sqlClient.executeUpdate("DELETE FROM FakeRepo");
        }
    }

    @Test
    @DisplayName("Registry wires real SqlDatabaseManager with in-memory SQLite bootstrapped by migration resource file")
    void e2e_smokeTest() throws Exception {
        var platform = new SimplePlatformHandle("SmokeTest", List.of());
        var manager = new SqlDatabaseManager(platform, SqliteConnectionConfig.inMemory());

        var registry = new RepositoryRegistry();
        registry.register(new SimplePlatformHandle("E2E", List.of()), this)
                .onConfigure(ctx -> {
                    ctx.publish(FakeRepository.class, manager);
                    ctx.bindImpl(FakeRepository.class, SqliteOnlyFakeRepositoryImpl.class);
                });

        registry.closeRegistration().whenComplete((result, err) -> {
            assertNull(err);
            assertTrue(result);
        }).get(5, TimeUnit.SECONDS);

        FakeRepository repo = registry.get(FakeRepository.class);
        assertNotNull(repo);
        assertInstanceOf(SqliteOnlyFakeRepositoryImpl.class, repo);

        // Flyweight: second call returns the exact same instance
        assertSame(repo, registry.get(FakeRepository.class));

        // Verify table bootstrapped via migration execution
        assertTrue(manager.tableExists("FakeRepo"));
        assertTrue(manager.tableHasColumn("FakeRepo", "key"));
        assertTrue(manager.tableHasColumn("FakeRepo", "value"));

        // Store data
        repo.put(42, "oops");
        repo.put(42, "everything");
        repo.put(-7, "not that");

        // Dialect change is rejected when cached repo does not support the new dialect

        // Attempting to switch to MySQL must be rejected because the cached impl is SQLite-only.
        // validateNewConfig throws before HikariCP ever tries to connect.
        assertThrows(IllegalStateException.class, () ->
                manager.setSqlConnectionConfig(
                        new MySqlConnectionConfig("127.0.0.1", 3306, "test", 1, "user", "pass")));

        // Dialect must be unchanged
        assertEquals(SqlDialect.SQLITE, manager.activeDialect());

        // Validate stored data remains accessible
        assertEquals("everything", repo.get(42));
        assertNull(repo.get(99));
        assertEquals(2, (int) manager.executeQuery("SELECT COUNT(*) FROM FakeRepo", rs -> {rs.next(); return rs.getInt(1);}));

        // Remove data
        repo.clear();
        assertEquals(0, (int) manager.executeQuery("SELECT COUNT(*) FROM FakeRepo", rs -> {rs.next(); return rs.getInt(1);}));

        manager.shutdown(1, TimeUnit.SECONDS);
    }
}
