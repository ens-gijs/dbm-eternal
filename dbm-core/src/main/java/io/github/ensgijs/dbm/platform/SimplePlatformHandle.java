package io.github.ensgijs.dbm.platform;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A lightweight, general-purpose {@link PlatformHandle} implementation backed by simple field values.
 * <p>
 * Suitable for use in tests, standalone applications, or any environment that does not have
 * a platform-specific plugin handle available.
 * </p>
 *
 * @param name         A unique name identifying this platform / plugin instance.
 * @param dependencies Names of other platform handles that this one depends on. Used by
 *                     {@code RepositoryRegistry} to establish
 *                     callback ordering during bootstrapping.
 */
public record SimplePlatformHandle(@NotNull String name, @NotNull List<String> dependencies) implements PlatformHandle {

    public SimplePlatformHandle(@NotNull String name, @NotNull List<String> dependencies) {
        this.name = name;
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    public boolean dependsOn(@NotNull PlatformHandle other) {
        return dependencies.contains(other.name());
    }
}
