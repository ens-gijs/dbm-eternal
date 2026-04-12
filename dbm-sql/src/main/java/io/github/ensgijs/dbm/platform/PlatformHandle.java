package io.github.ensgijs.dbm.platform;

import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Represents the host environment (e.g., a plugin) that owns a database.
 * <p>
 * Implementors supply a name for logging and pool identification, a data folder
 * for SQLite file placement, and optional dependency relationships used by the
 * {@link io.github.ensgijs.dbm.repository.RepositoryRegistry} to order bootstrap callbacks.
 * </p>
 *
 * @see SimplePlatformHandle
 */
public interface PlatformHandle {
    /** @return A unique name identifying this platform or plugin instance. */
    @NotNull String name();

    /** @return The directory used for data files such as SQLite database files. */
    @NotNull File dataFolder();

    /**
     * Returns {@code true} if this platform depends on {@code other}, meaning {@code other}
     * should be initialized first. Used to order
     * {@link io.github.ensgijs.dbm.repository.RepositoryRegistry.RegistrationHelper} callbacks.
     */
    boolean dependsOn(@NotNull PlatformHandle other);

    /**
     * Comparator helper for dependency ordering.
     * @return -1 if A depends on B; 1 if B depends on A; 0 if there is no dependency relationship.
     */
    static int dependencyComparator(PlatformHandle a, PlatformHandle b) {
        if (a.dependsOn(b)) return -1;
        if (b.dependsOn(a)) return 1;
        return 0;
    }
}