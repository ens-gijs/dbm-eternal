package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.repository.Repository;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record DbmConfig(@NotNull SqlConnectionConfig sqlConnectionConfig, @NotNull List<Class<? extends Repository>> provides) {

    public DbmConfig(@NotNull SqlConnectionConfig sqlConnectionConfig) {
        this(sqlConnectionConfig, Collections.emptyList());
    }

    public DbmConfig(@NotNull SqlConnectionConfig sqlConnectionConfig, @NotNull List<Class<? extends Repository>> provides) {
        this.sqlConnectionConfig = sqlConnectionConfig;
        if (provides.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("provides list must not contain null elements");
        this.provides = List.copyOf(provides);
    }

    public DbmConfig(@NotNull SqlConnectionConfig sqlConnectionConfig, @NotNull ClassLoader classLoader, @NotNull List<String> provides) throws InvalidDbmConfigException {
        this(sqlConnectionConfig, asRepositoryTypes(classLoader, provides));
    }

    private static List<Class<? extends Repository>> asRepositoryTypes(ClassLoader classLoader, List<String> provides) throws InvalidDbmConfigException {
        List<Class<? extends Repository>> providesTypes = new ArrayList<>(provides.size());
        InvalidDbmConfigException exOut = null;
        for (String interfaceName : provides) {
            if (interfaceName == null || interfaceName.isBlank()) continue;
            try {
                Class<? extends Repository> registryType = Class.forName(interfaceName, false, classLoader)
                        .asSubclass(Repository.class);
                providesTypes.add(registryType);
            } catch (Exception ex) {
                if (exOut == null) {
                    exOut = new InvalidDbmConfigException(ex);
                } else {
                    exOut.addSuppressed(ex);
                }
            }
        }
        if (exOut != null) throw exOut;
        return providesTypes;
    }

    public static class InvalidDbmConfigException extends Exception {
        public InvalidDbmConfigException() {}
        public InvalidDbmConfigException(String message) {
            super(message);
        }
        public InvalidDbmConfigException(Exception cause) {
            super(cause);
        }
        public InvalidDbmConfigException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
