package io.github.ensgijs.dbm.util;

import io.github.ensgijs.dbm.sql.SqlStatementSplitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SqlStatementSplitterTest {

    @Test
    @DisplayName("Should split simple statements by semicolon")
    void testSimpleSplit() {
        String sql = "SELECT * FROM users; DELETE FROM logs;";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(2, result.size());
        assertEquals("SELECT * FROM users", result.get(0));
        assertEquals("DELETE FROM logs", result.get(1));
    }

    @Test
    @DisplayName("Should ignore semicolons inside single and double quotes")
    void testQuotes() {
        String sql = "INSERT INTO t (col) VALUES ('val;ue'); SELECT \"col;umn\" FROM t;";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("'val;ue'"));
        assertTrue(result.get(1).contains("\"col;umn\""));
    }

    @Test
    @DisplayName("Should ignore semicolons inside single-line comments")
    void testSingleLineComments() {
        String sql = "SELECT 1; -- comment with ; semicolon\nSELECT 2;";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(2, result.size());
        assertEquals("SELECT 1", result.get(0));
        assertEquals("SELECT 2", result.get(1));
    }

    @Test
    @DisplayName("Should ignore semicolons inside multi-line comments")
    void testMultiLineComments() {
        String sql = "SELECT 1; /** multi-line \n * ; comment */ SELECT 2;";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(2, result.size());
        assertEquals("SELECT 1", result.get(0));
        assertEquals("SELECT 2", result.get(1));
    }

    @Test
    @DisplayName("Should handle escaped quotes correctly")
    void testEscapedQuotes() {
        String sql = "SELECT 'It''s a trap;'; SELECT 2;";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(2, result.size());
        assertEquals("SELECT 'It''s a trap;'", result.get(0));
    }

    @Test
    @DisplayName("Should return empty list for empty input")
    void testEmptyInput() {
        List<String> result = SqlStatementSplitter.splitStatement("   ;  ");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should parse a single statement without a semicolon")
    void testSingleStatementNoSemicolon() {
        String sql = "SELECT * FROM users";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(1, result.size());
        assertEquals("SELECT * FROM users", result.get(0));
    }

    @Test
    @DisplayName("Should parse a single statement with a semicolon")
    void testSingleStatementWithSemicolon() {
        String sql = "SELECT * FROM users;";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(1, result.size());
        assertEquals("SELECT * FROM users", result.get(0));
    }

    @Test
    @DisplayName("Should include the final statement even if it lacks a semicolon")
    void testLastStatementMissingSemicolon() {
        String sql = "INSERT INTO a VALUES (1); UPDATE b SET val = 2; SELECT * FROM c";
        List<String> result = SqlStatementSplitter.splitStatement(sql);

        assertEquals(3, result.size());
        assertEquals("INSERT INTO a VALUES (1)", result.get(0));
        assertEquals("UPDATE b SET val = 2", result.get(1));
        assertEquals("SELECT * FROM c", result.get(2));
    }

    @Test
    @DisplayName("Should handle unclosed single quotes by throwing StatementSplitException")
    void testUnclosedSingleQuote() {
        String sql = "INSERT INTO t, k VALUES ('ok', 'unclosed; string;\nSELECT 1;";
        assertThrows(SqlStatementSplitter.StatementSplitException.class,
                () -> SqlStatementSplitter.splitStatement(sql));
    }

    @Test
    @DisplayName("Should handle unclosed double quotes by throwing StatementSplitException")
    void testUnclosedDoubleQuote() {
        String sql = "INSERT INTO t, k VALUES (\"ok\", \"unclosed; string;\nSELECT 1;";
        assertThrows(SqlStatementSplitter.StatementSplitException.class,
                () -> SqlStatementSplitter.splitStatement(sql));
    }

    @Test
    @DisplayName("Should handle unclosed multi-line comments by throwing StatementSplitException")
    void testUnclosedMultiLineComment() {
        String sql = "SELECT 1; /* Unclosed comment... SELECT 2;";
        assertThrows(SqlStatementSplitter.StatementSplitException.class,
                () -> SqlStatementSplitter.splitStatement(sql));
    }
}