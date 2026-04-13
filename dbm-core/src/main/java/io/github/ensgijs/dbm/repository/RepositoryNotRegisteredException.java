package io.github.ensgijs.dbm.repository;

public class RepositoryNotRegisteredException extends RepositoryInitializationException {
    public RepositoryNotRegisteredException(Class<?> repoInterface) {
        super("No implementation registered for: " + repoInterface.getName());
    }

    public RepositoryNotRegisteredException(String message) {
        super(message);
    }
}
