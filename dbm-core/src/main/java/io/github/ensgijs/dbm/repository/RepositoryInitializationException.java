package io.github.ensgijs.dbm.repository;


import io.github.ensgijs.dbm.sql.DatabaseException;

public class RepositoryInitializationException extends DatabaseException {
    public RepositoryInitializationException(String message) {
        super(message);
    }

    public RepositoryInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
