package io.github.ensgijs.dbm.sql;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseExceptionTest {

    // ---- wrap ----

    @Test
    void wrap_wrapsNonDatabaseException() {
        RuntimeException original = new RuntimeException("boom");
        DatabaseException wrapped = DatabaseException.wrap(original);
        assertInstanceOf(DatabaseException.class, wrapped);
        assertSame(original, wrapped.getCause());
    }

    @Test
    void wrap_doesNotDoubleWrapDatabaseException() {
        DatabaseException original = new DatabaseException("already wrapped");
        DatabaseException result = DatabaseException.wrap(original);
        assertSame(original, result, "wrap() should return the same instance when already a DatabaseException");
    }

    @Test
    void wrap_extractsMessageFromCauseChain() {
        RuntimeException noMsg = new RuntimeException((String) null) {
            @Override public String getMessage() { return null; }
        };
        SQLException sqlEx = new SQLException("SQL went wrong");
        noMsg.initCause(sqlEx);

        DatabaseException wrapped = DatabaseException.wrap(noMsg);
        assertNotNull(wrapped.getMessage(), "Message should not be null");
        assertTrue(wrapped.getMessage().contains("SQL went wrong"),
                "Message should be sourced from deepest non-blank cause — was: " + wrapped.getMessage());
    }

    // ---- getSqlExceptionCause ----

    @Test
    void getSqlExceptionCause_returnsNullWhenNoCauseChain() {
        DatabaseException ex = new DatabaseException("no cause");
        assertNull(ex.getSqlExceptionCause());
    }

    @Test
    void getSqlExceptionCause_findsDirectSqlException() {
        SQLException sqlEx = new SQLException("sql error");
        DatabaseException dbEx = new DatabaseException("wrapper", sqlEx);
        assertSame(sqlEx, dbEx.getSqlExceptionCause());
    }

    @Test
    void getSqlExceptionCause_findsNestedSqlException() {
        SQLException sqlEx = new SQLException("deep sql error");
        RuntimeException middle = new RuntimeException("middle", sqlEx);
        DatabaseException dbEx = new DatabaseException("outer", middle);
        assertSame(sqlEx, dbEx.getSqlExceptionCause());
    }

    @Test
    void getSqlExceptionCause_returnsNullWhenOnlyRuntimeExceptions() {
        RuntimeException cause = new RuntimeException("not sql");
        DatabaseException dbEx = new DatabaseException("wrapper", cause);
        assertNull(dbEx.getSqlExceptionCause());
    }

    // ---- constructors ----

    @Test
    void constructorWithCause_derivesMessage() {
        RuntimeException cause = new RuntimeException("root cause message");
        DatabaseException ex = new DatabaseException(cause);
        assertNotNull(ex.getMessage(), "Message should not be null when constructed from cause");
        assertTrue(ex.getMessage().contains("root cause message"));
    }

    @Test
    void constructorWithMessageAndCause_keepsProvidedMessage() {
        DatabaseException ex = new DatabaseException("custom message", new RuntimeException());
        assertEquals("custom message", ex.getMessage());
    }
}
