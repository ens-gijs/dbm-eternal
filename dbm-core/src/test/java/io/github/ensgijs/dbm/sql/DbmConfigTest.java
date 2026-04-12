package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.repository.Repository;
import io.github.ensgijs.dbm.repository.RepositoryApi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DbmConfigTest {

    @RepositoryApi
    private interface FakeRepo extends Repository {}

    private static final SqlConnectionConfig SQLITE_CFG =
            new SqlConnectionConfig(SqlDialect.SQLITE, "test", 1, null, 0, null, null);

    // ---- single-arg constructor ----

    @Test
    void singleArgCtor_hasEmptyProvides() {
        DbmConfig cfg = new DbmConfig(SQLITE_CFG);
        assertEquals(SQLITE_CFG, cfg.sqlConnectionConfig());
        assertTrue(cfg.provides().isEmpty());
    }

    // ---- two-arg constructor with class list ----

    @Test
    void twoArgCtor_storesProvides() {
        DbmConfig cfg = new DbmConfig(SQLITE_CFG, List.of(FakeRepo.class));
        assertEquals(1, cfg.provides().size());
        assertSame(FakeRepo.class, cfg.provides().get(0));
    }

    @Test
    void twoArgCtor_providesIsImmutable() {
        DbmConfig cfg = new DbmConfig(SQLITE_CFG, List.of(FakeRepo.class));
        assertThrows(UnsupportedOperationException.class, () -> cfg.provides().add(FakeRepo.class));
    }

    @Test
    void twoArgCtor_nullElementInProvidesThrows() {
        List<Class<? extends Repository>> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new DbmConfig(SQLITE_CFG, withNull));
    }

    // ---- FQCN-based constructor ----

    @Test
    void fqcnCtor_resolvesClassByName() throws DbmConfig.InvalidDbmConfigException {
        String fqcn = FakeRepo.class.getName();
        DbmConfig cfg = new DbmConfig(SQLITE_CFG, FakeRepo.class.getClassLoader(), List.of(fqcn));
        assertEquals(1, cfg.provides().size());
        assertSame(FakeRepo.class, cfg.provides().get(0));
    }

    @Test
    void fqcnCtor_blankNamesSkipped() throws DbmConfig.InvalidDbmConfigException {
        DbmConfig cfg = new DbmConfig(SQLITE_CFG, getClass().getClassLoader(), List.of("", "  "));
        assertTrue(cfg.provides().isEmpty(), "Blank/empty FQCNs should be skipped");
    }

    @Test
    void fqcnCtor_unknownClassThrowsInvalidDbmConfigException() {
        assertThrows(DbmConfig.InvalidDbmConfigException.class,
                () -> new DbmConfig(SQLITE_CFG, getClass().getClassLoader(),
                        List.of("com.example.DoesNotExist")));
    }

    @Test
    void fqcnCtor_nonRepositoryClassThrowsInvalidDbmConfigException() {
        // String is not a Repository
        assertThrows(DbmConfig.InvalidDbmConfigException.class,
                () -> new DbmConfig(SQLITE_CFG, getClass().getClassLoader(),
                        List.of(String.class.getName())));
    }

    @Test
    void fqcnCtor_multipleFailuresAllSuppressed() {
        DbmConfig.InvalidDbmConfigException thrown = assertThrows(
                DbmConfig.InvalidDbmConfigException.class,
                () -> new DbmConfig(SQLITE_CFG, getClass().getClassLoader(),
                        List.of("com.Bad1", "com.Bad2")));
        assertEquals(1, thrown.getSuppressed().length,
                "Second failure should be attached as a suppressed exception");
    }
}
