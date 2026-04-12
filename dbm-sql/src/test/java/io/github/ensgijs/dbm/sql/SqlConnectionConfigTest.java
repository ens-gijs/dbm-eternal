package io.github.ensgijs.dbm.sql;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class SqlConnectionConfigTest {

    // ---- MySqlConnectionConfig ----

    @Nested
    class MySqlTests {

        @Test
        void getDbUrl_buildsJdbcUrl() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig(
                    "db.example.com", 3306, "mydb", 5, "user", "pass");
            String url = cfg.getDbUrl();
            assertTrue(url.startsWith("jdbc:mysql://db.example.com:3306/mydb"),
                    "URL should contain host:port/db — was: " + url);
            assertTrue(url.contains("rewriteBatchedStatements=true"),
                    "MySQL URL should include rewriteBatchedStatements");
        }

        @Test
        void configurePool_setsDriverAndCreds() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig(
                    "localhost", 3306, "mydb", 5, "root", "secret");
            HikariConfig hc = new HikariConfig();
            cfg.configurePool(hc);
            assertEquals("com.mysql.cj.jdbc.Driver", hc.getDriverClassName());
            assertEquals("root", hc.getUsername());
            assertEquals("secret", hc.getPassword());
            assertEquals(5, hc.getMaximumPoolSize());
            assertEquals("true", hc.getDataSourceProperties().getProperty("cachePrepStmts"));
            assertEquals("true", hc.getDataSourceProperties().getProperty("useServerPrepStmts"));
        }

        @Test
        void constructor_clampsMaxConnectionsBelowOne() {
            MySqlConnectionConfig cfg0 = new MySqlConnectionConfig("h", 3306, "db", 0, "u", "p");
            assertEquals(1, cfg0.maxConnections(), "maxConnections=0 should be clamped to 1");

            MySqlConnectionConfig cfgNeg = new MySqlConnectionConfig("h", 3306, "db", -5, "u", "p");
            assertEquals(1, cfgNeg.maxConnections(), "Negative maxConnections should be clamped to 1");
        }

        @Test
        void dialect_returnsMysql() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig("h", 3306, "db", 1, "u", "p");
            assertEquals(SqlDialect.MYSQL, cfg.dialect());
        }

        @Test
        void connectionId_containsUserAndHost() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig("myhost", 3306, "mydb", 1, "admin", "p");
            String id = cfg.connectionId();
            assertTrue(id.contains("admin"), "connectionId should contain username");
            assertTrue(id.contains("myhost"), "connectionId should contain host");
        }

        @Test
        void isEquivalent_nullReturnsFalse() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig("h", 3306, "db", 1, "u", "p");
            assertFalse(cfg.isEquivalent(null));
        }

        @Test
        void isEquivalent_differentTypeReturnsFalse() {
            MySqlConnectionConfig mysql = new MySqlConnectionConfig("h", 3306, "db", 1, "u", "p");
            SqliteConnectionConfig sqlite = new SqliteConnectionConfig(new File("/data/db.db"));
            assertFalse(mysql.isEquivalent(sqlite));
        }

        @Test
        void isEquivalent_comparesAllFields() {
            MySqlConnectionConfig base = new MySqlConnectionConfig("host", 3306, "db", 5, "user", "pass");

            assertTrue(base.isEquivalent(
                    new MySqlConnectionConfig("host", 3306, "db", 5, "user", "pass")));

            assertFalse(base.isEquivalent(
                    new MySqlConnectionConfig("other", 3306, "db", 5, "user", "pass")),
                    "Different host should not be equivalent");
            assertFalse(base.isEquivalent(
                    new MySqlConnectionConfig("host", 3307, "db", 5, "user", "pass")),
                    "Different port should not be equivalent");
            assertFalse(base.isEquivalent(
                    new MySqlConnectionConfig("host", 3306, "other", 5, "user", "pass")),
                    "Different database should not be equivalent");
            assertFalse(base.isEquivalent(
                    new MySqlConnectionConfig("host", 3306, "db", 10, "user", "pass")),
                    "Different maxConnections should not be equivalent");
            assertFalse(base.isEquivalent(
                    new MySqlConnectionConfig("host", 3306, "db", 5, "other", "pass")),
                    "Different username should not be equivalent");
            assertFalse(base.isEquivalent(
                    new MySqlConnectionConfig("host", 3306, "db", 5, "user", "other")),
                    "Different password should not be equivalent");
        }

        @Test
        void toString_showsAllFields() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig("127.0.0.1", 3306, "mydb", 5, "root", "secret");
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
        void toString_emptyPassword_showsEmpty() {
            MySqlConnectionConfig cfg = new MySqlConnectionConfig("h", 3306, "db", 1, "u", "");
            String s = cfg.toString();
            assertFalse(s.contains("*"), "Empty password should not show asterisks");
        }
    }

    // ---- SqliteConnectionConfig ----

    @Nested
    class SqliteTests {

        @Test
        void getDbUrl_buildsFilePathUrl() {
            SqliteConnectionConfig cfg = new SqliteConnectionConfig(new File("/data/mydb.db"));
            String url = cfg.getDbUrl();
            assertTrue(url.startsWith("jdbc:sqlite:"), "URL should use jdbc:sqlite: scheme — was: " + url);
            assertTrue(url.contains("mydb.db"), "SQLite URL should contain database file name — was: " + url);
            assertTrue(url.contains("journal_mode=WAL"), "SQLite URL should set WAL mode — was: " + url);
        }

        @Test
        void of_appendsDbExtension() {
            File folder = new File("/data");
            SqliteConnectionConfig cfg = SqliteConnectionConfig.of(folder, "mydb");
            assertTrue(cfg.file().getName().endsWith(".db"),
                    "of() factory should append .db extension");
            assertEquals("mydb.db", cfg.file().getName());
        }

        @Test
        void configurePool_setsDriverAndPoolSizeOne() {
            SqliteConnectionConfig cfg = new SqliteConnectionConfig(new File("/data/mydb.db"));
            HikariConfig hc = new HikariConfig();
            cfg.configurePool(hc);
            assertEquals("org.sqlite.JDBC", hc.getDriverClassName());
            assertEquals(1, hc.getMaximumPoolSize());
        }

        @Test
        void maxConnections_alwaysOne() {
            SqliteConnectionConfig cfg = new SqliteConnectionConfig(new File("/data/mydb.db"));
            assertEquals(1, cfg.maxConnections());
        }

        @Test
        void dialect_returnsSqlite() {
            SqliteConnectionConfig cfg = new SqliteConnectionConfig(new File("/data/mydb.db"));
            assertEquals(SqlDialect.SQLITE, cfg.dialect());
        }

        @Test
        void isEquivalent_nullReturnsFalse() {
            SqliteConnectionConfig cfg = new SqliteConnectionConfig(new File("/data/mydb.db"));
            assertFalse(cfg.isEquivalent(null));
        }

        @Test
        void isEquivalent_differentTypeReturnsFalse() {
            SqliteConnectionConfig sqlite = new SqliteConnectionConfig(new File("/data/db.db"));
            MySqlConnectionConfig mysql = new MySqlConnectionConfig("h", 3306, "db", 1, "u", "p");
            assertFalse(sqlite.isEquivalent(mysql));
        }

        @Test
        void isEquivalent_comparesAbsolutePath() {
            SqliteConnectionConfig a = new SqliteConnectionConfig(new File("/data/mydb.db"));
            SqliteConnectionConfig b = new SqliteConnectionConfig(new File("/data/mydb.db"));
            SqliteConnectionConfig c = new SqliteConnectionConfig(new File("/data/other.db"));
            assertTrue(a.isEquivalent(b), "Same path should be equivalent");
            assertFalse(a.isEquivalent(c), "Different path should not be equivalent");
        }

        @Test
        void toString_showsFileAndDialect() {
            SqliteConnectionConfig cfg = new SqliteConnectionConfig(new File("/data/mydb.db"));
            String s = cfg.toString();
            assertTrue(s.contains("SQLite") || s.contains("SQLITE"), "toString should include dialect");
            assertTrue(s.contains("mydb.db"), "toString should include file name");
            assertFalse(s.contains("host"), "SQLite toString should not mention host");
            assertFalse(s.contains("port"), "SQLite toString should not mention port");
        }

        @Test
        void constructor_nullFileThrows() {
            assertThrows(NullPointerException.class, () -> new SqliteConnectionConfig(null));
        }
    }
}
