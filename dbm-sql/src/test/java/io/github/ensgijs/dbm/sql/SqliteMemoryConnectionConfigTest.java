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
    @DisplayName("Blank name throws IllegalArgumentException")
    void blankName_throws() {
        assertThrows(IllegalArgumentException.class, () -> new SqliteMemoryConnectionConfig("  "));
    }

    @Test
    @DisplayName("Null name throws NullPointerException")
    void nullName_throws() {
        assertThrows(NullPointerException.class, () -> new SqliteMemoryConnectionConfig(null));
    }

    @Test
    @DisplayName("dialect() returns SQLITE")
    void dialect_isSqlite() {
        assertEquals(SqlDialect.SQLITE, new SqliteMemoryConnectionConfig("test").dialect());
    }

    @Test
    @DisplayName("maxConnections() is 1")
    void maxConnections_isOne() {
        assertEquals(1, new SqliteMemoryConnectionConfig("test").maxConnections());
    }

    @Test
    @DisplayName("connectionId() contains the database name")
    void connectionId_containsName() {
        assertTrue(new SqliteMemoryConnectionConfig("mydb").connectionId().contains("mydb"));
    }

    @Test
    @DisplayName("isEquivalent: same name → true, different name → false, null → false")
    void isEquivalent() {
        var a = new SqliteMemoryConnectionConfig("db");
        var b = new SqliteMemoryConnectionConfig("db");
        var c = new SqliteMemoryConnectionConfig("other");
        assertTrue(a.isEquivalent(b));
        assertFalse(a.isEquivalent(c));
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
    @DisplayName("inMemory(name) factory uses supplied name")
    void inMemoryFactory_namedUsesName() {
        var cfg = SqliteConnectionConfig.inMemory("my-test-db");
        assertTrue(cfg.connectionId().contains("my-test-db"));
    }

    @Test
    @DisplayName("Two auto-named inMemory() configs are not equivalent")
    void autoNamed_areNotEquivalent() {
        var cfgA = SqliteConnectionConfig.inMemory();
        var cfgB = SqliteConnectionConfig.inMemory();
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

    @Test
    @DisplayName("Two configs with the same name share data within the JVM")
    void sameName_sharedData() throws Exception {
        var cfgA = SqliteConnectionConfig.inMemory("shared-test-" + System.nanoTime());
        var cfgB = new SqliteMemoryConnectionConfig(
                ((SqliteMemoryConnectionConfig) cfgA).databaseName());

        var clientA = client(cfgA);
        clientA.executeUpdate("CREATE TABLE IF NOT EXISTS shared_data (val TEXT)");
        clientA.executeUpdate("INSERT INTO shared_data VALUES ('ping')");

        var clientB = client(cfgB);
        String val = clientB.executeQuery("SELECT val FROM shared_data LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
        assertEquals("ping", val, "Data written via clientA should be visible via clientB with same db name");
    }
}
