package io.github.ensgijs.dbm.repository;

public class RepositoryNotRegisteredException extends RepositoryInitializationException {
    public RepositoryNotRegisteredException(Class<?> repoInterface) {
        super("No concretion type has been nominated for repo interface: " + repoInterface.getName());
    }
}
