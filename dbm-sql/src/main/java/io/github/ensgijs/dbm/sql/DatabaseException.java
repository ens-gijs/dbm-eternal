package io.github.ensgijs.dbm.sql;

import io.github.ensgijs.dbm.util.BubbleUpException;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.CompletionException;

public class DatabaseException extends CompletionException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(Throwable cause) {
        super(makeMessage(cause), cause);
    }

    private static String makeMessage(Throwable ex) {
        String msg = ex.getClass().getSimpleName();
        do {
            if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
                msg = '[' + ex.getClass().getSimpleName() + "] " + ex.getMessage();
                break;
            }
        } while(ex != ex.getCause() && (ex = ex.getCause()) != null);
        return msg;
    }

    /// Scans down the cause chain and returns the first {@link SQLException} cause, if any.
    public @Nullable SQLException getSqlExceptionCause() {
        Throwable ex = this;
        while(ex != ex.getCause() && (ex = ex.getCause()) != null) {
            if (SQLException.class.isAssignableFrom(ex.getClass())) {
                return (SQLException) ex;
            }
        }
        return null;
    }

    public static DatabaseException wrap(Throwable cause) {
        // TODO: is there a way to reliably steal a snip of the cause's stack and inject it
        //  into the new one just below our caller?
        cause = BubbleUpException.unwrap(cause);
        if (!(cause instanceof DatabaseException dbex)) {
            DatabaseException dbex = new DatabaseException(cause);
            var trace = dbex.getStackTrace();
            dbex.setStackTrace(Arrays.copyOfRange(trace, 1, trace.length));
            return dbex;
        }
        return dbex;
    }
}
