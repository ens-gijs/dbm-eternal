package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.sql.DatabaseException;
import io.github.ensgijs.dbm.sql.SqlDialect;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MigrationSortTest {

    @Test
    void testComplexDependencySorting() throws DatabaseException {
        Migration m1 = createMigration("core", 1, Set.of());
        Migration m2 = createMigration("economy", 1, Set.of(new Migration.Key("core", 1)));
        Migration m3 = createMigration("shop", 1, Set.of(new Migration.Key("economy", 1)));

        // Pass them in reverse order to ensure the sorter actually sorts
        List<Migration> sorted = Migration.sort(List.of(m3, m2, m1));

        assertEquals("core", sorted.get(0).key().name());
        assertEquals("economy", sorted.get(1).key().name());
        assertEquals("shop", sorted.get(2).key().name());
    }

    @Test
    void testCircularDependencyThrows() {
        Migration m1 = createMigration("A", 1, Set.of(new Migration.Key("B", 1)));
        Migration m2 = createMigration("B", 1, Set.of(new Migration.Key("A", 1)));
        Migration m3 = createMigration("C", 1, Set.of(new Migration.Key("A", 1)));

        assertThrows(DatabaseException.class, () -> Migration.sort(List.of(m1, m2, m3)));
    }

    @Test
    void testMissingDependencyThrows() {
        // "shop" depends on "economy.1" which is not in the input set
        Migration shop = createMigration("shop", 1, Set.of(new Migration.Key("economy", 1)));

        assertThrows(DatabaseException.class, () -> Migration.sort(List.of(shop)),
                "Sort should throw when a declared dependency is missing from the input set");
    }

    @Test
    void testEmptyCollectionReturnsEmptyList() throws DatabaseException {
        List<Migration> result = Migration.sort(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void testSingleMigrationNoDepsReturnsItself() throws DatabaseException {
        Migration m = createMigration("solo", 1, Set.of());
        List<Migration> result = Migration.sort(List.of(m));
        assertEquals(1, result.size());
        assertSame(m, result.get(0));
    }

    @Test
    void testIndependentMigrationsOrderedByVersion() throws DatabaseException {
        // Three migrations with no dependencies — should be sorted by version ascending
        Migration v3 = createMigration("area", 300, Set.of());
        Migration v1 = createMigration("area", 100, Set.of());
        Migration v2 = createMigration("area", 200, Set.of());

        List<Migration> sorted = Migration.sort(List.of(v3, v1, v2));

        assertEquals(100, sorted.get(0).version());
        assertEquals(200, sorted.get(1).version());
        assertEquals(300, sorted.get(2).version());
    }

    @Test
    void testDependencyAlwaysPrecedesDependent() throws DatabaseException {
        // B depends on A at a higher version — A must still come first regardless of version ordering
        Migration a = createMigration("core", 1000, Set.of());
        Migration b = createMigration("feature", 1, Set.of(new Migration.Key("core", 1000)));

        List<Migration> sorted = Migration.sort(List.of(b, a));

        assertEquals("core", sorted.get(0).key().name(), "Dependency must precede the dependent");
        assertEquals("feature", sorted.get(1).key().name());
    }

    private Migration createMigration(String name, long version, Set<Migration.Key> deps) {
        return new Migration(
                new Migration.Key(name, version),
                new Migration.SqlSource(SqlDialect.SQLITE, List.of("SELECT 1")),
                deps,
                "TestPlugin"
        );
    }
}
