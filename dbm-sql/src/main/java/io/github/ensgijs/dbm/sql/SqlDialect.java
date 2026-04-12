package io.github.ensgijs.dbm.sql;

public enum SqlDialect {
    UNDEFINED("UNDEFINED"),
    SQLITE("SQLite"),
    MYSQL("MySQL");

    private final String display;

    SqlDialect(String display) {
        this.display = display;
    }

    @Override
    public String toString() {
        return display;
    }
}