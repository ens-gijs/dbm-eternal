package io.github.ensgijs.dbm.sql;

import java.util.ArrayList;
import java.util.List;

public final class SqlStatementSplitter {
    private SqlStatementSplitter() {}

    /**
     * Splits a string of SQL statements by semicolons, respecting quotes and comments.
     * @param statements The raw SQL string containing multiple statements.
     * @return A list of individual SQL statement strings.
     * @throws StatementSplitException if an unclosed string or multi-line comment is found.
     */
    public static List<String> splitStatement(String statements) {
        List<String> split = new ArrayList<>();

        // State: '"', '\'', '-', (single-line), '*' (multi-line), or null
        Character inside = null;
        int start = 0;
        int openCharPos = -1;
        int length = statements.length();

        for (int i = 0; i < length; i++) {
            char currentChar = statements.charAt(i);

            if (inside != null) {
                // Case: Single-line comment (--)
                if (inside == '-') {
                    if (currentChar == '\n') {
                        inside = null;
                        start = i + 1;
                    }
                }
                // Case: Multi-line comment (/* */)
                else if (inside == '*') {
                    if (currentChar == '*' && i + 1 < length && statements.charAt(i + 1) == '/') {
                        inside = null;
                        i++; // Skip the closing '/'
                        start = i + 1;
                        openCharPos = -1;
                    }
                }
                // Case: Close quoted string
                else if (currentChar == inside) {
                    if (i + 1 < length && statements.charAt(i + 1) == inside) {
                        i++;
                    } else {
                        inside = null;
                        openCharPos = -1;
                    }
                }
            } else {
                // Check for multi-line comment start /*
                if (currentChar == '/' && i + 1 < length && statements.charAt(i + 1) == '*') {
                    addStatement(split, statements, start, i);
                    inside = '*';
                    openCharPos = i++;
                }
                // Check for single-line comment start --
                else if (currentChar == '-' && i + 1 < length && statements.charAt(i + 1) == '-') {
                    addStatement(split, statements, start, i);
                    inside = '-';
                    i++;
                }
                else if (currentChar == '"') {
                    inside = '"';
                    openCharPos = i;
                } else if (currentChar == '\'') {
                    inside = '\'';
                    openCharPos = i;
                }
                else if (currentChar == ';') {
                    addStatement(split, statements, start, i);
                    start = i + 1;
                }
            }
        }

        // Final cleanup
        if (start < length) {
            if (inside == null) {
                addStatement(split, statements, start, length);
            } else if (inside == '\'' || inside == '"') {
                throw new StatementSplitException(String.format(
                        "Unclosed quote opened at position %d in query %d starting at position %d",
                        openCharPos + 1, split.size() + 1, start + 1));
            } else if (inside == '*') {
                throw new StatementSplitException(String.format(
                        "Unclosed multi-line comment opened at position %d in query %d starting at position %d",
                        openCharPos + 1, split.size() + 1, start + 1));
            }
        }

        return split;
    }

    /**
     * Helper to trim and add non-empty strings to the list
     */
    private static void addStatement(List<String> list, String text, int start, int end) {
        String v = text.substring(start, end).trim();
        if (!v.isEmpty()) {
            list.add(v);
        }
    }

    public static class StatementSplitException extends DatabaseException {

        public StatementSplitException(String message) {
            super(message);
        }
    }
}
