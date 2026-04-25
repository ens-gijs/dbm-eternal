package io.github.ensgijs.dbm.repository;

import io.github.ensgijs.dbm.sql.SqlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryImplTest {

    @RepositoryImpl(dialects = {SqlDialect.SQLITE})
    static class SqliteOnly {}

    @RepositoryImpl(dialects = {SqlDialect.MYSQL, SqlDialect.SQLITE})
    static class MultiDialect {}

    @RepositoryImpl(dialects = {SqlDialect.MYSQL, SqlDialect.MYSQL})
    static class DuplicateDialect {}

    @RepositoryImpl(dialects = {SqlDialect.UNDEFINED})
    static class WithUndefined {}

    static class NoAnnotation {}

    // -----------------------------------------------------------------------
    // supportedDialectsOf
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Missing annotation throws IllegalStateException")
    void missingAnnotation_throws() {
        assertThrows(IllegalStateException.class, () -> Repository.supportedDialectsOf(NoAnnotation.class));
    }

    @Test
    @DisplayName("Single-dialect annotation returns correct set")
    void singleDialect_correct() {
        assertEquals(Set.of(SqlDialect.SQLITE), Repository.supportedDialectsOf(SqliteOnly.class));
    }

    @Test
    @DisplayName("Multi-dialect annotation returns correct set")
    void multiDialect_correct() {
        assertEquals(Set.of(SqlDialect.MYSQL, SqlDialect.SQLITE), Repository.supportedDialectsOf(MultiDialect.class));
    }

    @Test
    @DisplayName("Duplicate dialect entries are deduplicated")
    void duplicateDialects_deduplicated() {
        assertEquals(Set.of(SqlDialect.MYSQL), Repository.supportedDialectsOf(DuplicateDialect.class));
    }

    @Test
    @DisplayName("UNDEFINED in dialects array throws IllegalStateException")
    void undefinedInDialects_throws() {
        assertThrows(IllegalStateException.class, () -> Repository.supportedDialectsOf(WithUndefined.class));
    }

    // -----------------------------------------------------------------------
    // supportsDialect
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("supportsDialect returns true for declared dialect")
    void supportsDialect_true() {
        assertTrue(Repository.supportsDialect(MultiDialect.class, SqlDialect.MYSQL));
        assertTrue(Repository.supportsDialect(MultiDialect.class, SqlDialect.SQLITE));
    }

    @Test
    @DisplayName("supportsDialect returns false for undeclared dialect")
    void supportsDialect_false() {
        assertFalse(Repository.supportsDialect(SqliteOnly.class, SqlDialect.MYSQL));
    }

    @Test
    @DisplayName("supportsDialect with UNDEFINED active throws IllegalArgumentException")
    void supportsDialect_undefinedActive_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> Repository.supportsDialect(SqliteOnly.class, SqlDialect.UNDEFINED));
    }

    @Test
    @DisplayName("supportsDialect with missing annotation propagates IllegalStateException")
    void supportsDialect_missingAnnotation_throws() {
        assertThrows(IllegalStateException.class,
                () -> Repository.supportsDialect(NoAnnotation.class, SqlDialect.SQLITE));
    }
}
