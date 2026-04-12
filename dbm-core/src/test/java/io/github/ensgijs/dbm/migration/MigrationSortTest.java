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

    private Migration createMigration(String name, long version, Set<Migration.Key> deps) {
        return new Migration(
                new Migration.Key(name, version),
                new Migration.SqlSource(SqlDialect.SQLITE, List.of("SELECT 1")),
                deps,
                "TestPlugin"
        );
    }
}
