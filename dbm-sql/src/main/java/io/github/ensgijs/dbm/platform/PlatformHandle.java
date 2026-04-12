package io.github.ensgijs.dbm.platform;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public interface PlatformHandle {
    @NotNull String name();
    @NotNull File dataFolder();

    /**
     * Platform-specific dependency check.
     */
    boolean dependsOn(@NotNull PlatformHandle other);

    /// @return -1 if A depends on B; 1 if B depends on A; 0 if there is no dependency relationship.
    static int dependencyComparator(PlatformHandle a, PlatformHandle b) {
        if (a.dependsOn(b)) return -1;
        if (b.dependsOn(a)) return 1;
        return 0;
    }
}