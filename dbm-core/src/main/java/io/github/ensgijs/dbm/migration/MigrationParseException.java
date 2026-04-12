package io.github.ensgijs.dbm.migration;

import java.io.IOException;

public class MigrationParseException extends IOException {
    public MigrationParseException(String message) {
        super(message);
    }

    public MigrationParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
