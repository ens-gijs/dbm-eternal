package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.platform.SimplePlatformHandle;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlConnectionConfigTest {

    private static final SimplePlatformHandle PLATFORM =
            new SimplePlatformHandle("test", new File("/data"), List.of());

    // ---- getDbUrl ----

    @Test
    void getDbUrl_mysql_buildsJdbcUrl() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(
                SqlDialect.MYSQL, "mydb", 5, "db.example.com", 3306, "user", "pass");
        String url = cfg.getDbUrl(PLATFORM);
        assertTrue(url.startsWith("jdbc:mysql://db.example.com:3306/mydb"),
                "URL should contain host:port/db — was: " + url);
        assertTrue(url.contains("rewriteBatchedStatements=true"),
                "MySQL URL should include rewriteBatchedStatements");
    }

    @Test
    void getDbUrl_sqlite_buildsFilePathUrl() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(
                SqlDialect.SQLITE, "mydb", 1, null, 0, null, null);
        String url = cfg.getDbUrl(PLATFORM);
        assertTrue(url.startsWith("jdbc:sqlite:"), "URL should use jdbc:sqlite: scheme — was: " + url);
        assertTrue(url.contains("mydb.db"), "SQLite URL should contain database file name — was: " + url);
        assertTrue(url.contains("journal_mode=WAL"), "SQLite URL should set WAL mode — was: " + url);
    }

    // ---- maxConnections clamping ----

    @Test
    void constructor_clampsMaxConnectionsBelowOne() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(SqlDialect.MYSQL, "db", 0, "h", 3306, "u", "p");
        assertEquals(1, cfg.maxConnections(), "maxConnections below 1 should be clamped to 1");

        SqlConnectionConfig cfg2 = new SqlConnectionConfig(SqlDialect.MYSQL, "db", -5, "h", 3306, "u", "p");
        assertEquals(1, cfg2.maxConnections(), "Negative maxConnections should be clamped to 1");
    }

    // ---- isEquivalent ----

    @Test
    void isEquivalent_nullReturnsFalse() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(SqlDialect.SQLITE, "db", 1, null, 0, null, null);
        assertFalse(cfg.isEquivalent(null));
    }

    @Test
    void isEquivalent_differentDialectReturnsFalse() {
        SqlConnectionConfig a = new SqlConnectionConfig(SqlDialect.SQLITE, "db", 1, null, 0, null, null);
        SqlConnectionConfig b = new SqlConnectionConfig(SqlDialect.MYSQL, "db", 1, "h", 3306, "u", "p");
        assertFalse(a.isEquivalent(b));
    }

    @Test
    void isEquivalent_sqlite_onlyComparesDatabase() {
        SqlConnectionConfig a = new SqlConnectionConfig(SqlDialect.SQLITE, "db", 1, null, 0, null, null);
        SqlConnectionConfig b = new SqlConnectionConfig(SqlDialect.SQLITE, "db", 99, "ignored", 9999, "x", "y");
        assertTrue(a.isEquivalent(b), "SQLite equivalence should only check database name");

        SqlConnectionConfig c = new SqlConnectionConfig(SqlDialect.SQLITE, "other", 1, null, 0, null, null);
        assertFalse(a.isEquivalent(c));
    }

    @Test
    void isEquivalent_mysql_comparesAllRelevantFields() {
        SqlConnectionConfig base = new SqlConnectionConfig(SqlDialect.MYSQL, "db", 5, "host", 3306, "user", "pass");

        assertTrue(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "db", 5, "host", 3306, "user", "pass")));

        assertFalse(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "other", 5, "host", 3306, "user", "pass")),
                "Different database should not be equivalent");
        assertFalse(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "db", 5, "other-host", 3306, "user", "pass")),
                "Different host should not be equivalent");
        assertFalse(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "db", 5, "host", 3307, "user", "pass")),
                "Different port should not be equivalent");
        assertFalse(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "db", 10, "host", 3306, "user", "pass")),
                "Different maxConnections should not be equivalent");
        assertFalse(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "db", 5, "host", 3306, "other", "pass")),
                "Different username should not be equivalent");
        assertFalse(base.isEquivalent(
                new SqlConnectionConfig(SqlDialect.MYSQL, "db", 5, "host", 3306, "user", "other")),
                "Different password should not be equivalent");
    }

    // ---- toString ----

    @Test
    void toString_sqlite_doesNotShowMysqlFields() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(SqlDialect.SQLITE, "mydb", 1, null, 0, null, null);
        String s = cfg.toString();
        assertTrue(s.contains("SQLITE") || s.contains("SQLite"), "toString should include dialect");
        assertTrue(s.contains("mydb"), "toString should include database name");
        assertFalse(s.contains("host"), "SQLite toString should not mention host");
        assertFalse(s.contains("port"), "SQLite toString should not mention port");
    }

    @Test
    void toString_mysql_showsAllFields() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(SqlDialect.MYSQL, "mydb", 5, "127.0.0.1", 3306, "root", "secret");
        String s = cfg.toString();
        assertTrue(s.contains("MySQL") || s.contains("MYSQL"), "toString should include dialect");
        assertTrue(s.contains("mydb"), "toString should include database name");
        assertTrue(s.contains("127.0.0.1"), "toString should include host");
        assertTrue(s.contains("3306"), "toString should include port");
        assertTrue(s.contains("root"), "toString should include username");
        assertFalse(s.contains("secret"), "toString should mask password");
        assertTrue(s.contains("*"), "toString should show asterisks for non-empty password");
    }

    @Test
    void toString_mysql_emptyPassword_showsEmpty() {
        SqlConnectionConfig cfg = new SqlConnectionConfig(SqlDialect.MYSQL, "db", 1, "h", 3306, "u", "");
        String s = cfg.toString();
        assertFalse(s.contains("*"), "Empty password should not show asterisks");
    }
}
