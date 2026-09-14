package com.dfiles.service;

/**
 * A target SQL database for {@link SqlTableWriter}. None of these support the literal
 * {@code CREATE OR REPLACE TABLE} syntax (that's a MariaDB/DuckDB/Snowflake-ism), so each dialect
 * instead spells out its own idiomatic "drop the table if it exists, then create it" sequence.
 * They also disagree on how to start a transaction, on column type names, on identifier length
 * limits, and — most sharply — on whether a single {@code INSERT} can carry more than one row of
 * literal values at all.
 */
public enum SqlDialect {

    SQLITE("SQLite") {
        @Override String typeName(SqlColumnType type) {
            return switch (type) { case INTEGER -> "INTEGER"; case REAL -> "REAL"; case TEXT -> "TEXT"; };
        }
        @Override String dropTableIfExists(String table) { return "DROP TABLE IF EXISTS " + table + ";\n"; }
        @Override String beginTransaction() { return "BEGIN TRANSACTION;\n"; }
        @Override boolean supportsMultiRowValues() { return true; }
        @Override int maxRowsPerBatch() { return Integer.MAX_VALUE; }
        @Override int maxIdentifierLength() { return 128; }
    },

    MYSQL("MySQL") {
        @Override String typeName(SqlColumnType type) {
            return switch (type) { case INTEGER -> "INT"; case REAL -> "DOUBLE"; case TEXT -> "TEXT"; };
        }
        @Override String dropTableIfExists(String table) { return "DROP TABLE IF EXISTS " + table + ";\n"; }
        @Override String beginTransaction() { return "START TRANSACTION;\n"; }
        @Override boolean supportsMultiRowValues() { return true; }
        @Override int maxRowsPerBatch() { return Integer.MAX_VALUE; }
        @Override int maxIdentifierLength() { return 64; }
    },

    POSTGRESQL("PostgreSQL") {
        @Override String typeName(SqlColumnType type) {
            return switch (type) { case INTEGER -> "INTEGER"; case REAL -> "DOUBLE PRECISION"; case TEXT -> "TEXT"; };
        }
        @Override String dropTableIfExists(String table) { return "DROP TABLE IF EXISTS " + table + ";\n"; }
        @Override String beginTransaction() { return "BEGIN;\n"; }
        @Override boolean supportsMultiRowValues() { return true; }
        @Override int maxRowsPerBatch() { return Integer.MAX_VALUE; }
        @Override int maxIdentifierLength() { return 63; }
    },

    MSSQL("Microsoft SQL Server") {
        @Override String typeName(SqlColumnType type) {
            return switch (type) { case INTEGER -> "INT"; case REAL -> "FLOAT"; case TEXT -> "NVARCHAR(MAX)"; };
        }
        @Override String dropTableIfExists(String table) {
            return "IF OBJECT_ID(N'" + table + "', N'U') IS NOT NULL DROP TABLE " + table + ";\n";
        }
        @Override String beginTransaction() { return "BEGIN TRANSACTION;\n"; }
        @Override boolean supportsMultiRowValues() { return true; }
        // T-SQL hard limit: an INSERT ... VALUES row constructor list tops out at 1000 rows.
        @Override int maxRowsPerBatch() { return 1000; }
        @Override int maxIdentifierLength() { return 128; }
    },

    ORACLE("Oracle") {
        @Override String typeName(SqlColumnType type) {
            return switch (type) { case INTEGER -> "INTEGER"; case REAL -> "FLOAT"; case TEXT -> "VARCHAR2(4000)"; };
        }
        @Override String dropTableIfExists(String table) {
            // Oracle has no DROP TABLE IF EXISTS; -942 is ORA-00942 "table or view does not exist".
            return "BEGIN\n" +
                    "    EXECUTE IMMEDIATE 'DROP TABLE " + table + "';\n" +
                    "EXCEPTION\n" +
                    "    WHEN OTHERS THEN\n" +
                    "        IF SQLCODE != -942 THEN RAISE; END IF;\n" +
                    "END;\n/\n";
        }
        // Oracle has no explicit "start transaction" statement — one begins implicitly with the
        // first DML statement, so there's nothing to emit here.
        @Override String beginTransaction() { return ""; }
        // Oracle's INSERT has no multi-row VALUES list at all (that's an Oracle-specific gap, not
        // a length limit like MSSQL's) — SqlTableWriter falls back to INSERT ALL for this dialect.
        @Override boolean supportsMultiRowValues() { return false; }
        @Override int maxRowsPerBatch() { return 250; } // rows per INSERT ALL block, a practical chunk size
        @Override int maxIdentifierLength() { return 30; } // conservative pre-12.2 limit
    };

    private final String displayName;

    SqlDialect(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    @Override
    public String toString() { return displayName; }

    abstract String typeName(SqlColumnType type);
    abstract String dropTableIfExists(String table);
    abstract String beginTransaction();
    abstract boolean supportsMultiRowValues();
    abstract int maxRowsPerBatch();
    abstract int maxIdentifierLength();
}
