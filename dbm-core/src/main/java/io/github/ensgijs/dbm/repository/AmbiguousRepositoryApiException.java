package io.github.ensgijs.dbm.repository;

public class AmbiguousRepositoryApiException extends RepositoryInitializationException {
    public AmbiguousRepositoryApiException(String message) {
        super(message);
    }

    public AmbiguousRepositoryApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
