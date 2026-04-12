package io.github.ensgijs.dbm.migration;

import io.github.ensgijs.dbm.sql.ExecutionContext;

/**
 * A stub implementation for testing Java-based migrations.
 * Functional additions allow tests to verify execution and context passing.
 */
public class FakeProgrammaticMigration implements Migration.ProgrammaticMigration {
    // Static fields to track execution in unit test environment
    public static boolean wasCalled = false;
    public static ExecutionContext capturedContext = null;

    @Override
    public void migrate(ExecutionContext ctx) {
        wasCalled = true;
        capturedContext = ctx;
    }

    /**
     * Resets tracking state between tests.
     */
    public static void reset() {
        wasCalled = false;
        capturedContext = null;
    }
}
