package io.github.ensgijs.dbm.platform;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

public record SimplePlatformHandle(@NotNull String name, @NotNull File dataFolder, @NotNull List<String> dependencies) implements PlatformHandle {

    public SimplePlatformHandle(@NotNull String name, @NotNull File dataFolder, @NotNull List<String> dependencies) {
        this.name = name;
        this.dataFolder = dataFolder;
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    public boolean dependsOn(@NotNull PlatformHandle other) {
        return dependencies.contains(other.name());
    }
}
