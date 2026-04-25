package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMemoryConnectionConfigTest {

    private static SqlClient client(SqlConnectionConfig cfg) {
        return new SqlClient(new SimplePlatformHandle("test", List.of()), cfg);
    }

    // -----------------------------------------------------------------------
    // Config contract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dialect() returns SQLITE")
    void dialect_isSqlite() {
        assertEquals(SqlDialect.SQLITE, new SqliteMemoryConnectionConfig().dialect());
    }

    @Test
    @DisplayName("maxConnections() is 1")
    void maxConnections_isOne() {
        assertEquals(1, new SqliteMemoryConnectionConfig().maxConnections());
    }

    @Test
    @DisplayName("isEquivalent: same instance → true, different instance → false, null → false")
    void isEquivalent() {
        var a = new SqliteMemoryConnectionConfig();
        var b = new SqliteMemoryConnectionConfig();
        assertNotSame(a, b);
        assertTrue(a.isEquivalent(a));
        assertFalse(a.isEquivalent(b));
        assertFalse(a.isEquivalent(null));
    }

    @Test
    @DisplayName("inMemory() factory returns SqliteMemoryConnectionConfig with SQLITE dialect")
    void inMemoryFactory_returnsCorrectType() {
        var cfg = SqliteConnectionConfig.inMemory();
        assertInstanceOf(SqliteMemoryConnectionConfig.class, cfg);
        assertEquals(SqlDialect.SQLITE, cfg.dialect());
    }

    @Test
    @DisplayName("Two inMemory() configs are not equivalent")
    void autoNamed_areNotEquivalent() {
        var cfgA = SqliteConnectionConfig.inMemory();
        var cfgB = SqliteConnectionConfig.inMemory();
        assertNotSame(cfgA, cfgB);
        assertFalse(cfgA.isEquivalent(cfgB));
    }

    // -----------------------------------------------------------------------
    // Round-trip SQL via SqlClient
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Round-trip: CREATE TABLE / INSERT / SELECT via SqlClient")
    void roundTrip_createInsertSelect() throws Exception {
        var cfg = SqliteConnectionConfig.inMemory();
        var sqlClient = client(cfg);
        sqlClient.executeUpdate("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
        sqlClient.executeUpdate("INSERT INTO items VALUES (1, 'hello')");
        String result = sqlClient.executeQuery("SELECT name FROM items WHERE id = 1", rs -> {
            if (rs.next()) return rs.getString(1);
            return null;
        });
        assertEquals("hello", result);
    }

    @Test
    @DisplayName("Two auto-named databases are isolated (table in A absent from B)")
    void autoNamed_isolation() throws Exception {
        var clientA = client(SqliteConnectionConfig.inMemory());
        clientA.executeUpdate("CREATE TABLE isolation_test (id INTEGER PRIMARY KEY)");
        clientA.executeUpdate("INSERT INTO isolation_test VALUES (42)");

        var clientB = client(SqliteConnectionConfig.inMemory());
        long count = clientB.executeQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE name='isolation_test'",
                rs -> { rs.next(); return rs.getLong(1); });
        assertEquals(0L, count, "Table created in A should not exist in independent B");
    }
}
