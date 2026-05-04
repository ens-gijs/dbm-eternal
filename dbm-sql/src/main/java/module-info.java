/**
 * Core SQL layer for the dbm library — connection pooling, statement execution,
 * batch/upsert helpers, async support, and reusable utilities.
 * <p>
 * Module name is intentionally stable; once published, renaming it is a breaking
 * change for any JPMS consumer regardless of SemVer.
 * <p>
 * All packages are exported. As of 0.x there is no internal/implementation split.
 * When adding a new package, decide explicitly whether it is part of the public
 * API; un-exported packages are unreachable from JPMS consumers and that's the
 * intended discipline.
 */
module io.github.ensgijs.dbm.sql {
    requires transitive com.zaxxer.hikari;
    requires transitive java.sql;
    requires java.logging;
    requires static org.jetbrains.annotations;

    exports io.github.ensgijs.dbm.sql;
    exports io.github.ensgijs.dbm.util;
    exports io.github.ensgijs.dbm.util.function;
    exports io.github.ensgijs.dbm.util.io;
    exports io.github.ensgijs.dbm.util.objects;
    exports io.github.ensgijs.dbm.util.threading;
}
