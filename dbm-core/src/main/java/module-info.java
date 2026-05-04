/**
 * Schema migration engine + repository registry built on dbm-sql.
 * <p>
 * Module name is intentionally stable; once published, renaming it is a breaking
 * change for any JPMS consumer regardless of SemVer.
 * <p>
 * All packages are exported. As of 0.x there is no internal/implementation split.
 * When adding a new package, decide explicitly whether it is part of the public
 * API; un-exported packages are unreachable from JPMS consumers and that's the
 * intended discipline.
 */
module io.github.ensgijs.dbm.core {
    requires transitive io.github.ensgijs.dbm.sql;
    requires transitive com.zaxxer.hikari;
    requires java.logging;
    requires static org.jetbrains.annotations;

    exports io.github.ensgijs.dbm.migration;
    exports io.github.ensgijs.dbm.platform;
    exports io.github.ensgijs.dbm.repository;
}
